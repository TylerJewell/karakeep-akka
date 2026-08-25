package io.akka.karakeep.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.karakeep.domain.Attachment;
import io.akka.karakeep.domain.TaggingStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a failing tagging job does. SPEC-001 R5, R6, R15, R18, R19, R20, R21. */
class TaggingFailureIntegrationTest extends TaggingTestBase {

  private int seq = 0;

  private String ingestText(String ownerId, String text) {
    String id = "fail-" + ownerId + "-" + seq++;
    componentClient
        .forEventSourcedEntity(id)
        .method(BookmarkEntity::ingestText)
        .invoke(new BookmarkEntity.IngestText(ownerId, null, text));
    return id;
  }

  private List<String> tagNames(String id) {
    return componentClient
        .forEventSourcedEntity(id)
        .method(BookmarkEntity::get)
        .invoke()
        .attachments()
        .stream()
        .map(Attachment::name)
        .sorted()
        .toList();
  }

  @Test
  void fourAttemptsAreMadeBeforeGivingUp() {
    MODEL.reset(List.of(ScriptedModel.Reply.unavailable()));
    String id = ingestText("owner-down", "a note");

    var job = runTagging(id);

    assertEquals(TaggingWorkflow.FAILED, job.outcome());
    assertEquals(4, job.attemptsMade());
    assertEquals(4, MODEL.callCount(), "one request per attempt, not one per attempt per retry");
    assertEquals(TaggingStatus.FAILURE, statusOf(id));
  }

  @Test
  void statusStaysPendingUntilTheRetriesAreSpent() {
    MODEL.reset(List.of(ScriptedModel.Reply.unavailable()));
    String id = ingestText("owner-watched", "a note");

    // Read the bookmark at the moment of every request to the model, so what a reader sees
    // between attempts is observed rather than inferred from the final reading. Sampling on a
    // timer instead would report "pending was seen at some point", which is true of a job that
    // marked the bookmark failed after its first attempt.
    List<TaggingStatus> whenAsked = Collections.synchronizedList(new ArrayList<>());
    MODEL.onRequest(() -> whenAsked.add(statusOf(id)));

    var job = runTagging(id);

    assertEquals(4, job.attemptsMade());
    assertEquals(4, whenAsked.size(), "the model was asked once per attempt");
    assertEquals(
        List.of(
            TaggingStatus.PENDING,
            TaggingStatus.PENDING,
            TaggingStatus.PENDING,
            TaggingStatus.PENDING),
        whenAsked,
        "the bookmark read pending on every attempt, including the last");
    assertEquals(TaggingStatus.FAILURE, statusOf(id));
  }

  @Test
  void aModelThatRecoversInsideTheAttemptBudgetSucceeds() {
    MODEL.reset(
        List.of(
            ScriptedModel.Reply.unavailable(),
            ScriptedModel.Reply.unavailable(),
            ScriptedModel.Reply.unavailable(),
            ScriptedModel.Reply.ok("{\"tags\":[\"Recovered\"]}")));
    String id = ingestText("owner-recovers", "a note");

    var job = runTagging(id);

    assertEquals(TaggingWorkflow.TAGGED, job.outcome());
    assertEquals(4, job.attemptsMade());
    assertEquals(TaggingStatus.SUCCESS, statusOf(id));
    assertEquals(List.of("Recovered"), tagNames(id));
  }

  @Test
  void aModelThatIgnoresThePromptFailsTheSameWayAsOneThatIsDown() {
    MODEL.reset(List.of(ScriptedModel.Reply.ok("I am not going to do that")));
    String id = ingestText("owner-ignores", "a note");

    var job = runTagging(id);

    assertEquals(TaggingWorkflow.FAILED, job.outcome());
    assertEquals(4, job.attemptsMade());
    assertEquals(TaggingStatus.FAILURE, statusOf(id));
  }

  @Test
  void modelErrorLeavesTagsUntouched() {
    String id = ingestText("owner-untouched", "a note");
    MODEL.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[\"OldAi\"]}")));
    runTagging(id);

    MODEL.reset(List.of(ScriptedModel.Reply.unavailable()));
    var job = runTagging(id);

    assertEquals(TaggingWorkflow.FAILED, job.outcome());
    assertEquals(List.of("OldAi"), tagNames(id), "a failed attempt wrote nothing");
    assertEquals(TaggingStatus.FAILURE, statusOf(id));
  }

  @Test
  void aSkippedJobReportsSuccess() {
    MODEL.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[\"Never\"]}")));
    String id = ingestText("owner-off", "a note");
    componentClient
        .forKeyValueEntity("owner-off")
        .method(UserEntity::setAutoTagging)
        .invoke(false);

    var job = runTagging(id);

    assertEquals(TaggingWorkflow.SKIPPED, job.outcome());
    assertEquals(TaggingStatus.SUCCESS, statusOf(id));
    assertEquals(0, MODEL.callCount());
    assertEquals(List.of(), tagNames(id));
  }

  /**
   * R15 and R18. A bookmark that is not there fails an attempt rather than leaving the
   * runtime retrying a step that cannot succeed, so the job settles like any other failure.
   */
  @Test
  void taggingAnUnknownBookmarkGivesUpAfterFourAttempts() {
    MODEL.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[\"Never\"]}")));

    var job = runTagging("no-such-bookmark-at-all");

    assertEquals(TaggingWorkflow.FAILED, job.outcome());
    assertEquals(4, job.attemptsMade());
    assertEquals(0, MODEL.callCount(), "nothing was asked of the model");
  }

  /**
   * R15. Asked of the entity rather than of the workflow: a workflow step that raises is retried
   * by the runtime, so the workflow never settles on an outcome and what a test could observe
   * there is a timeout rather than the rejection.
   */
  @Test
  void unknownBookmarkIsRejected() {
    var thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                componentClient
                    .forEventSourcedEntity("no-such-bookmark")
                    .method(BookmarkEntity::get)
                    .invoke());
    assertTrue(thrown.getMessage().contains("no-such-bookmark"));
  }
}
