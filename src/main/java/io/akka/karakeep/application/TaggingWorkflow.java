package io.akka.karakeep.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import com.typesafe.config.Config;
import io.akka.karakeep.domain.Bookmark;
import io.akka.karakeep.domain.ResolvedTag;
import io.akka.karakeep.domain.TagExtraction;
import io.akka.karakeep.domain.TaggingDecision;
import io.akka.karakeep.domain.TaggingStatus;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One bookmark's tagging job, and its retries. SPEC-001 R1–R3, R5, R6, R16, R18–R21.
 *
 * <p>The attempt count is kept in the workflow's own state rather than left to a step retry
 * strategy, because R19 is a claim about what a reader sees <em>between</em> attempts: the
 * bookmark must read {@code pending} on every one of them and {@code failure} only after the
 * last. A strategy that retries a step invisibly satisfies the outcome and leaves that
 * unobservable.
 */
@Component(id = "tagging")
public class TaggingWorkflow extends Workflow<TaggingWorkflow.Job> {

  /**
   * @param attemptsMade attempts that have already run and failed
   * @param outcome null while the job is still running
   */
  public record Job(
      String bookmarkId, int attemptsMade, String lastFailure, String outcome, List<String> tags) {}

  public record Start(String bookmarkId) {}

  /** The four terminal readings, as the workflow reports them. */
  public static final String TAGGED = "tagged";

  public static final String SKIPPED = "skipped";
  public static final String FAILED = "failed";
  public static final String REJECTED = "rejected";

  private static final Logger logger = LoggerFactory.getLogger(TaggingWorkflow.class);

  private final ComponentClient client;
  private final ModelClient model;
  private final int maxAttempts;

  public TaggingWorkflow(ComponentClient client, Config config) {
    this.client = client;
    // R18 — four attempts in all, matching the source's one attempt plus three retries. It is
    // configuration so a benchmark can vary it, and the default is the source's number.
    this.maxAttempts = config.getInt("karakeep.tagging.max-attempts");
    this.model =
        new ModelClient(
            config.getString("karakeep.inference.base-url"),
            config.getString("karakeep.inference.model"),
            config.getDuration("karakeep.inference.timeout"));
  }

  /**
   * The attempt step waits on a language model, and the default step timeout is five seconds —
   * short enough that a slow model would look like a failed attempt and spend a retry.
   */
  @Override
  public WorkflowSettings settings() {
    return WorkflowSettingsBuilder.newBuilder()
        .defaultStepTimeout(Duration.ofSeconds(120))
        .build();
  }

  public Effect<Done> start(Start cmd) {
    return effects()
        .updateState(new Job(cmd.bookmarkId(), 0, null, null, List.of()))
        .transitionTo(TaggingWorkflow::attempt)
        .thenReply(Done.getInstance());
  }

  public ReadOnlyEffect<Job> job() {
    return effects().reply(currentState());
  }

  public StepEffect attempt() {
    Job state = currentState();

    // R15 and R18 — a bookmark that is not there is a failed attempt like any other, not a
    // step for the runtime to retry until somebody notices. Left to raise, the step is
    // retried for as long as the workflow exists and the job never settles, which is a
    // reading no caller can act on.
    Bookmark bookmark;
    try {
      bookmark =
          client.forEventSourcedEntity(state.bookmarkId()).method(BookmarkEntity::get).invoke();
    } catch (RuntimeException e) {
      return afterFailedAttempt(
          new FailedAttempt(
              state,
              state.attemptsMade() + 1,
              FAILED,
              "the bookmark could not be read",
              e.getMessage()));
    }

    boolean autoTaggingEnabled =
        client
            .forKeyValueEntity(bookmark.ownerId())
            .method(UserEntity::settings)
            .invoke()
            .autoTaggingEnabled();

    TaggingDecision.Outcome decision = TaggingDecision.decide(bookmark, autoTaggingEnabled);
    // A skip is an attempt that ran and decided not to ask, so it counts as one. The
    // source counts it the same way: the job it skipped in was dequeued and completed.
    int attemptsMade = state.attemptsMade() + 1;

    // R1, R2 and R20 — a skip is a completed job, so the status reads success even though
    // nothing was tagged.
    if (decision instanceof TaggingDecision.Skip skip) {
      logger.info("bookmark {} skipped: {}", state.bookmarkId(), skip.because());
      return end(
          new Ending(
              new Job(state.bookmarkId(), attemptsMade, state.lastFailure(), null, List.of()),
              SKIPPED,
              TaggingStatus.SUCCESS,
              skip.because()));
    }

    // R16 — a bookmark with no content of any kind raises, and so takes the same route as any
    // other raising attempt. It is retried like one, because in the source the check that
    // rejects it throws from inside the same job the model failures throw from, and a rejection
    // that gave up sooner would disagree with the source on how many attempts were made.
    if (decision instanceof TaggingDecision.Reject reject) {
      return afterFailedAttempt(
          new FailedAttempt(
              state, attemptsMade, REJECTED, "the bookmark has no content", reject.because()));
    }

    String prompt = ((TaggingDecision.Ask) decision).prompt();

    List<String> inferredNames;
    try {
      // R5 and R6 — an unreachable model and a reply that does not parse fail the same way, and
      // both leave the bookmark's tags alone because nothing is written before this returns.
      inferredNames = TagExtraction.tagsFrom(model.infer(prompt));
    } catch (RuntimeException e) {
      return afterFailedAttempt(
          new FailedAttempt(
              state, attemptsMade, FAILED, e.getClass().getSimpleName(), e.getMessage()));
    }

    List<ResolvedTag> resolved =
        client
            .forEventSourcedEntity(bookmark.ownerId())
            .method(TagCatalogEntity::resolve)
            .invoke(inferredNames)
            .tags();

    client
        .forEventSourcedEntity(state.bookmarkId())
        .method(BookmarkEntity::applyInferredTags)
        .invoke(resolved);

    return end(
        new Ending(
            new Job(state.bookmarkId(), attemptsMade, null, null, inferredNames),
            TAGGED,
            TaggingStatus.SUCCESS,
            null));
  }

  /**
   * R18 and R19. Nothing is written to the bookmark on the way through here, which is what keeps
   * it reading {@code pending} between attempts; the status is only touched once the attempts are
   * gone.
   */
  /**
   * @param kind what went wrong, in words safe to log: the failure itself quotes the model's
   *     reply, which is about the owner's own bookmark
   */
  private record FailedAttempt(
      Job state, int attemptsMade, String outcome, String kind, String failure) {}

  private StepEffect afterFailedAttempt(FailedAttempt attempt) {
    logger.info(
        "bookmark {} attempt {} of {} failed ({})",
        attempt.state().bookmarkId(),
        attempt.attemptsMade(),
        maxAttempts,
        attempt.kind());
    Job next =
        new Job(
            attempt.state().bookmarkId(),
            attempt.attemptsMade(),
            attempt.failure(),
            null,
            List.of());
    if (attempt.attemptsMade() < maxAttempts) {
      return stepEffects().updateState(next).thenTransitionTo(TaggingWorkflow::attempt);
    }
    return end(new Ending(next, attempt.outcome(), TaggingStatus.FAILURE, attempt.failure()));
  }

  /** What a settled job writes and reports. One argument, because a workflow method with more
   * than one is read by the runtime as a step it cannot call. */
  private record Ending(Job state, String outcome, TaggingStatus status, String note) {}

  private StepEffect end(Ending ending) {
    try {
      client
          .forEventSourcedEntity(ending.state().bookmarkId())
          .method(BookmarkEntity::setTaggingStatus)
          .invoke(ending.status());
    } catch (RuntimeException e) {
      // A bookmark that was never there has no status to carry, and the job's own outcome
      // is the answer in that case.
      logger.info(
          "bookmark {} has no status to set: {}", ending.state().bookmarkId(), e.getMessage());
    }
    return stepEffects()
        .updateState(
            new Job(
                ending.state().bookmarkId(),
                ending.state().attemptsMade(),
                ending.note() == null ? ending.state().lastFailure() : ending.note(),
                ending.outcome(),
                ending.state().tags()))
        .thenEnd();
  }
}
