package io.akka.karakeep.bench;

import akka.javasdk.client.ComponentClient;
import akka.javasdk.testkit.TestKit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.ConfigFactory;
import io.akka.karakeep.application.BookmarkEntity;
import io.akka.karakeep.application.ScriptedModel;
import io.akka.karakeep.application.TagCatalogEntity;
import io.akka.karakeep.application.TaggingWorkflow;
import io.akka.karakeep.application.UserEntity;
import io.akka.karakeep.domain.Attachment;
import io.akka.karakeep.domain.Bookmark;
import io.akka.karakeep.domain.ResolvedTag;
import io.akka.karakeep.domain.TaggingStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs {@code karakeep-port/bench/workloads.json} through this port and writes
 * {@code port-answers.json} and {@code port-timings.json} beside the source's own.
 *
 * <p>Not a test. It asserts nothing about correctness — the comparison is between the two
 * answer files, not inside either of them — and a class that ran on every build would
 * rewrite them from a workload file that does not travel with this repository.
 *
 * <pre>mvn -q test-compile exec:java -Dexec.classpathScope=test
 *   -Dexec.mainClass=io.akka.karakeep.bench.BenchmarkRunner</pre>
 *
 * <p>The model is {@link ScriptedModel}, the same stand-in the tests use and the same
 * substitution the source runner makes: the network, answering each workload's scripted
 * reply. Everything else is the port's own — the workflow, its retry accounting, the
 * entities and their journal.
 */
public final class BenchmarkRunner {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Path BENCH = Path.of("..", "karakeep-port", "bench");
  private static final int WINDOWS = 5;
  private static final int REPETITIONS = 32;

  /** What a job reports when the runtime is still retrying a step that cannot succeed. */
  private static final String NEVER_SETTLED = "never-settled";

  private final ScriptedModel model;
  private final TestKit testKit;
  private final ComponentClient client;
  private int seq = 0;

  private BenchmarkRunner() throws Exception {
    this.model = new ScriptedModel(List.of(ScriptedModel.Reply.ok("{\"tags\":[]}")));
    this.testKit =
        new TestKit(
                TestKit.Settings.DEFAULT
                    .withServiceName("karakeep-akka")
                    .withAclDisabled()
                    .withAdditionalConfig(
                        ConfigFactory.parseString(
                            // Port 0: the benchmark must not collide with a copy of the
                            // service running on the registered number.
                            "akka.javasdk.dev-mode.http-port = 0\n"
                                + "karakeep.inference.base-url = \"" + model.baseUrl() + "\"\n"
                                + "karakeep.inference.model = scripted\n"
                                + "karakeep.inference.timeout = 30s\n"
                                + "karakeep.tagging.max-attempts = 4\n")))
            .start();
    this.client = testKit.getComponentClient();
  }

  public static void main(String[] args) throws Exception {
    BenchmarkRunner runner = new BenchmarkRunner();
    try {
      runner.run();
    } catch (RuntimeException e) {
      e.printStackTrace();
      throw e;
    } finally {
      runner.model.close();
    }
    System.exit(0);
  }

  private void run() throws Exception {
    ArrayNode workloads = (ArrayNode) JSON.readTree(BENCH.resolve("workloads.json").toFile());
    ObjectNode out = JSON.createObjectNode();
    ObjectNode answers = out.putObject("answers");

    for (JsonNode w : workloads) {
      String name = w.get("name").asText();
      switch (w.get("kind").asText()) {
        case "tagging" -> answers.set(name, tagging(w));
        case "arrival-order" -> answers.set(name, arrivalOrder(w));
        case "segmentation" -> answers.set(name, segmentation(w));
        case "sequence" -> answers.set(name, sequence(w));
        case "two-owners" -> answers.set(name, twoOwners(w));
        default -> throw new IllegalArgumentException("unknown workload kind for " + name);
      }
      System.out.println("ran " + name);
    }
    Files.writeString(BENCH.resolve("port-answers.json"), JSON.writerWithDefaultPrettyPrinter()
        .writeValueAsString(out));

    Files.writeString(BENCH.resolve("port-timings.json"), JSON.writerWithDefaultPrettyPrinter()
        .writeValueAsString(timings()));
  }

  // --- workloads ----------------------------------------------------------

  private String owner(boolean autoTaggingEnabled) {
    String id = "owner-" + (seq++);
    client.forKeyValueEntity(id).method(UserEntity::setAutoTagging).invoke(autoTaggingEnabled);
    return id;
  }

  private String bookmark(String ownerId, JsonNode spec) {
    String id = "bm-" + (seq++);
    if (spec == null || spec.isNull()) {
      return id;
    }
    if ("text".equals(spec.get("kind").asText())) {
      JsonNode text = spec.get("text");
      if (text == null || text.isNull()) {
        // A bookmark row with no content of any kind. The port refuses to ingest one, so
        // the state the source reaches by having no content row is reached here by
        // ingesting a text bookmark whose text is empty and then asking for it to be
        // tagged: both are "there is nothing here to infer from".
        client
            .forEventSourcedEntity(id)
            .method(BookmarkEntity::ingestText)
            .invoke(new BookmarkEntity.IngestText(ownerId, null, null));
        return id;
      }
      client
          .forEventSourcedEntity(id)
          .method(BookmarkEntity::ingestText)
          .invoke(new BookmarkEntity.IngestText(ownerId, null, text.asText()));
      return id;
    }
    client
        .forEventSourcedEntity(id)
        .method(BookmarkEntity::ingestLink)
        .invoke(
            new BookmarkEntity.IngestLink(
                ownerId,
                spec.get("url").asText(),
                text(spec, "title"),
                text(spec, "description")));
    return id;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private void attachByHand(String ownerId, String bookmarkId, String name) {
    ResolvedTag tag =
        client
            .forEventSourcedEntity(ownerId)
            .method(TagCatalogEntity::resolve)
            .invoke(List.of(name))
            .tags()
            .getFirst();
    client.forEventSourcedEntity(bookmarkId).method(BookmarkEntity::attachByHand).invoke(tag);
  }

  /** A tag in the owner's catalogue that nothing is attached to. */
  private void catalogue(String ownerId, String name) {
    client.forEventSourcedEntity(ownerId).method(TagCatalogEntity::resolve).invoke(List.of(name));
  }

  private record Outcome(TaggingWorkflow.Job job, List<String> statusWhenAttempted) {}

  private Outcome job(String bookmarkId) {
    String workflowId = "bench-" + (seq++);
    List<String> seen = java.util.Collections.synchronizedList(new ArrayList<>());
    seen.add(statusOf(bookmarkId));
    model.onRequest(() -> seen.add(statusOf(bookmarkId)));
    client
        .forWorkflow(workflowId)
        .method(TaggingWorkflow::start)
        .invoke(new TaggingWorkflow.Start(bookmarkId));

    // Bounded, and a job that runs out of time is an answer rather than a crash. A step
    // that raises for a reason no retry can fix — a bookmark that does not exist — is
    // retried by the runtime for as long as anyone waits, so "never settled" is what this
    // port does with it and belongs in the answer file where the comparison can see it.
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    TaggingWorkflow.Job settled = null;
    while (System.nanoTime() < deadline) {
      settled = client.forWorkflow(workflowId).method(TaggingWorkflow::job).invoke();
      if (settled != null && settled.outcome() != null) {
        break;
      }
      sleep(5);
    }
    model.onRequest(() -> {});
    if (settled == null || settled.outcome() == null) {
      int attempts = settled == null ? 0 : settled.attemptsMade();
      String failure = settled == null ? null : settled.lastFailure();
      settled = new TaggingWorkflow.Job(bookmarkId, attempts, failure, NEVER_SETTLED, List.of());
    }
    return new Outcome(settled, seen.stream().distinct().toList());
  }

  private String statusOf(String bookmarkId) {
    try {
      return client
          .forEventSourcedEntity(bookmarkId)
          .method(BookmarkEntity::get)
          .invoke()
          .taggingStatus()
          .name()
          .toLowerCase();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** The bookmark's tags as `name:attachedBy`, in the order it carries them. */
  private List<String> tagsOf(String bookmarkId) {
    try {
      Bookmark b =
          client.forEventSourcedEntity(bookmarkId).method(BookmarkEntity::get).invoke();
      return b.attachments().stream()
          .map(a -> a.name() + ":" + a.attachedBy().name().toLowerCase())
          .sorted()
          .toList();
    } catch (RuntimeException e) {
      return List.of();
    }
  }

  private ObjectNode tagging(JsonNode w) {
    // Auto-tagging is turned on for the seeding and set to the workload's value after it:
    // an ai tag only exists here because a previous run put it there, and a run cannot
    // happen with the preference already off.
    String ownerId = owner(true);
    String bookmarkId = bookmark(ownerId, w.get("bookmark"));
    for (JsonNode tag : optional(w, "existingTags")) {
      String name = tag.get("name").asText();
      if (tag.get("attachedBy").isNull()) {
        catalogue(ownerId, name);
      } else if ("human".equals(tag.get("attachedBy").asText())) {
        attachByHand(ownerId, bookmarkId, name);
      } else {
        model.reset(List.of(ScriptedModel.Reply.ok(tagsReply(List.of(name)))));
        job(bookmarkId);
      }
    }
    // The workload describes a bookmark waiting to be tagged, so the seeding run's own
    // status is put back: on the source the same state is reached by inserting the rows.
    // A workload with no bookmark at all — one that asks what tagging an unknown id does —
    // has nothing to put back.
    if (w.hasNonNull("bookmark")) {
      client
          .forEventSourcedEntity(bookmarkId)
          .method(BookmarkEntity::setTaggingStatus)
          .invoke(TaggingStatus.PENDING);
    }
    client
        .forKeyValueEntity(ownerId)
        .method(UserEntity::setAutoTagging)
        .invoke(w.get("autoTaggingEnabled").asBoolean());
    model.reset(replies(w));
    Outcome outcome = job(bookmarkId);

    ObjectNode node = JSON.createObjectNode();
    node.set("tags", JSON.valueToTree(tagsOf(bookmarkId)));
    node.put("taggingStatus", statusOf(bookmarkId));
    node.put("attempts", outcome.job().attemptsMade());
    node.put("modelCalls", model.callCount());
    node.put("failed", !TaggingWorkflow.TAGGED.equals(outcome.job().outcome())
        && !TaggingWorkflow.SKIPPED.equals(outcome.job().outcome()));
    node.put("outcome", outcome.job().outcome());
    node.set("statusWhenAttempted", JSON.valueToTree(outcome.statusWhenAttempted()));
    return node;
  }

  private static List<ScriptedModel.Reply> replies(JsonNode w) {
    if (w.hasNonNull("modelUnavailable") && w.get("modelUnavailable").asBoolean()) {
      return List.of(ScriptedModel.Reply.unavailable());
    }
    return List.of(ScriptedModel.Reply.ok(w.get("reply").asText()));
  }

  private ArrayNode arrivalOrder(JsonNode w) {
    List<String> names = new ArrayList<>();
    for (JsonNode row : w.get("rows")) {
      names.add(row.get("name").asText());
    }
    ArrayNode out = JSON.createArrayNode();
    for (List<String> order : permutations(names)) {
      String ownerId = owner(w.get("autoTaggingEnabled").asBoolean());
      String bookmarkId = bookmark(ownerId, w.get("bookmark"));
      model.reset(List.of(ScriptedModel.Reply.ok(tagsReply(order))));
      job(bookmarkId);
      ObjectNode node = JSON.createObjectNode();
      node.put("deliveredAs", String.join(",", order));
      node.set("tags", JSON.valueToTree(tagsOf(bookmarkId)));
      out.add(node);
    }
    return out;
  }

  private ArrayNode segmentation(JsonNode w) {
    String ownerId = owner(w.get("autoTaggingEnabled").asBoolean());
    String bookmarkId = bookmark(ownerId, w.get("bookmark"));
    ArrayNode out = JSON.createArrayNode();
    for (JsonNode batch : w.get("batches")) {
      List<String> tags = new ArrayList<>();
      for (JsonNode entry : batch) {
        tags.add(entry.get("tag").asText());
      }
      model.reset(List.of(ScriptedModel.Reply.ok(tagsReply(tags))));
      job(bookmarkId);
      ObjectNode node = JSON.createObjectNode();
      node.put("delivered", String.join(",", tags));
      node.set("tags", JSON.valueToTree(tagsOf(bookmarkId)));
      node.put("taggingStatus", statusOf(bookmarkId));
      out.add(node);
    }
    return out;
  }

  private ArrayNode sequence(JsonNode w) {
    String ownerId = owner(w.get("autoTaggingEnabled").asBoolean());
    String bookmarkId = bookmark(ownerId, w.get("bookmark"));
    ArrayNode out = JSON.createArrayNode();
    for (JsonNode step : w.get("steps")) {
      ObjectNode node = JSON.createObjectNode();
      if (step.hasNonNull("attachByHand")) {
        attachByHand(ownerId, bookmarkId, step.get("attachByHand").asText());
        node.put("step", "attachByHand:" + step.get("attachByHand").asText());
        node.set("tags", JSON.valueToTree(tagsOf(bookmarkId)));
        node.put("taggingStatus", statusOf(bookmarkId));
        node.put("attempts", 0);
        node.put("modelCalls", 0);
        node.set("statusWhenAttempted", JSON.valueToTree(List.of()));
        out.add(node);
        continue;
      }
      boolean unavailable = step.hasNonNull("modelUnavailable")
          && step.get("modelUnavailable").asBoolean();
      model.reset(unavailable
          ? List.of(ScriptedModel.Reply.unavailable())
          : List.of(ScriptedModel.Reply.ok(step.get("reply").asText())));
      Outcome outcome = job(bookmarkId);
      node.put("step", unavailable ? "modelUnavailable" : "reply:" + step.get("reply").asText());
      node.set("tags", JSON.valueToTree(tagsOf(bookmarkId)));
      node.put("taggingStatus", statusOf(bookmarkId));
      node.put("attempts", outcome.job().attemptsMade());
      node.put("modelCalls", model.callCount());
      node.set("statusWhenAttempted", JSON.valueToTree(outcome.statusWhenAttempted()));
      out.add(node);
    }
    return out;
  }

  private ObjectNode twoOwners(JsonNode w) {
    ObjectNode out = JSON.createObjectNode();
    ArrayNode perOwner = out.putArray("perOwner");
    List<String> tagIds = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      String ownerId = owner(w.get("autoTaggingEnabled").asBoolean());
      String bookmarkId = bookmark(ownerId, w.get("bookmark"));
      model.reset(List.of(ScriptedModel.Reply.ok(w.get("reply").asText())));
      job(bookmarkId);
      Bookmark b = client.forEventSourcedEntity(bookmarkId).method(BookmarkEntity::get).invoke();
      tagIds.add(b.attachments().stream().map(Attachment::tagId).findFirst().orElse(""));
      ObjectNode node = JSON.createObjectNode();
      node.put("owner", "owner" + i);
      node.set("tags", JSON.valueToTree(tagsOf(bookmarkId)));
      perOwner.add(node);
    }
    out.put("sameTagRow", tagIds.get(0).equals(tagIds.get(1)));
    return out;
  }

  // --- timing -------------------------------------------------------------

  /**
   * The same two figures the source runner takes, over the same shapes: one tagging job
   * whose model answers with no tags — which stops before anything is written — and one
   * whose model answers with three, on a bookmark that already carries one.
   *
   * <p>Each window runs over 32 distinct bookmarks, so no call is made with the same
   * arguments twice: a loop-invariant call is as free to be folded away as an unread one.
   */
  private ObjectNode timings() {
    ObjectNode out = JSON.createObjectNode();
    ObjectNode timing = out.putObject("timing");

    model.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[]}")));
    String decideOwner = owner(true);
    List<String> decideBookmarks = freshBookmarks(decideOwner, "a note about databases ");
    // Warm the JIT and the runtime's own caches before either figure is taken.
    timeInline(decideOwner, decideBookmarks.subList(0, 8), "{\"tags\":[]}", 1);
    timing
        .putObject("decide-only")
        .set("one-tagging-job",
            timeInline(decideOwner, decideBookmarks, "{\"tags\":[]}", WINDOWS));

    String writeOwner = owner(true);
    List<String> writeBookmarks = freshBookmarks(writeOwner, "a note about databases ");
    for (int i = 0; i < writeBookmarks.size(); i++) {
      attachByHand(writeOwner, writeBookmarks.get(i), "Existing" + i);
    }
    String three = "{\"tags\":[\"Alpha\",\"Beta\",\"Gamma\"]}";
    timing
        .putObject("decide-and-write")
        .set("one-tagging-job", timeInline(writeOwner, writeBookmarks, three, WINDOWS));

    // The same work again, driven through the workflow rather than inline, so the cost of
    // the port's own orchestration is a figure rather than a guess. It is reported beside
    // the two above and never as a ratio: its resolution is this runner's poll interval,
    // because a workflow reports that it has settled only when somebody asks.
    model.reset(List.of(ScriptedModel.Reply.ok(three)));
    String workflowOwner = owner(true);
    List<String> workflowBookmarks = freshBookmarks(workflowOwner, "a note about databases ");
    timing
        .putObject("through-the-workflow")
        .set("one-tagging-job", timeJobs(workflowBookmarks, WINDOWS));
    return out;
  }

  /**
   * One tagging job's work, done the way {@link TaggingWorkflow#attempt} does it and
   * without the workflow around it: read the bookmark, read the owner's preference,
   * decide, extract the tags, resolve them against the catalogue, apply them, set the
   * status. The source's {@code runTagging} is the same list of steps in the same order,
   * which is what makes the two figures comparable; what this leaves out is the workflow's
   * own orchestration, timed separately above.
   */
  private ObjectNode timeInline(
      String ownerId, List<String> bookmarks, String reply, int windows) {
    List<Double> perRun = new ArrayList<>(windows);
    for (int w = 0; w < windows; w++) {
      long started = System.nanoTime();
      for (String id : bookmarks) {
        Bookmark bookmark =
            client.forEventSourcedEntity(id).method(BookmarkEntity::get).invoke();
        boolean enabled =
            client
                .forKeyValueEntity(bookmark.ownerId())
                .method(UserEntity::settings)
                .invoke()
                .autoTaggingEnabled();
        var decision = io.akka.karakeep.domain.TaggingDecision.decide(bookmark, enabled);
        if (decision instanceof io.akka.karakeep.domain.TaggingDecision.Ask) {
          List<String> names = io.akka.karakeep.domain.TagExtraction.tagsFrom(reply);
          List<ResolvedTag> resolved =
              client
                  .forEventSourcedEntity(ownerId)
                  .method(TagCatalogEntity::resolve)
                  .invoke(names)
                  .tags();
          client
              .forEventSourcedEntity(id)
              .method(BookmarkEntity::applyInferredTags)
              .invoke(resolved);
        }
        client
            .forEventSourcedEntity(id)
            .method(BookmarkEntity::setTaggingStatus)
            .invoke(TaggingStatus.SUCCESS);
      }
      perRun.add((System.nanoTime() - started) / (double) bookmarks.size());
    }
    perRun.sort(Double::compare);
    double median = perRun.get(perRun.size() / 2);
    ObjectNode node = JSON.createObjectNode();
    node.put("repetitions", bookmarks.size());
    node.put("windows", windows);
    node.put("windowNanos", Math.round(median * bookmarks.size()));
    node.put("nanosPerRun", median);
    return node;
  }

  private List<String> freshBookmarks(String ownerId, String prefix) {
    List<String> ids = new ArrayList<>(REPETITIONS);
    for (int i = 0; i < REPETITIONS; i++) {
      String id = "bm-" + (seq++);
      client
          .forEventSourcedEntity(id)
          .method(BookmarkEntity::ingestText)
          .invoke(new BookmarkEntity.IngestText(ownerId, null, prefix + i));
      ids.add(id);
    }
    return ids;
  }

  private ObjectNode timeJobs(List<String> bookmarks, int windows) {
    List<Double> perRun = new ArrayList<>(windows);
    for (int w = 0; w < windows; w++) {
      long started = System.nanoTime();
      for (String id : bookmarks) {
        job(id);
      }
      perRun.add((System.nanoTime() - started) / (double) bookmarks.size());
    }
    perRun.sort(Double::compare);
    double median = perRun.get(perRun.size() / 2);
    ObjectNode node = JSON.createObjectNode();
    node.put("repetitions", bookmarks.size());
    node.put("windows", windows);
    node.put("windowNanos", Math.round(median * bookmarks.size()));
    node.put("nanosPerRun", median);
    return node;
  }

  // --- odds and ends ------------------------------------------------------

  private static String tagsReply(List<String> tags) {
    ArrayNode array = JSON.createArrayNode();
    tags.forEach(array::add);
    ObjectNode node = JSON.createObjectNode();
    node.set("tags", array);
    return node.toString();
  }

  private static List<List<String>> permutations(List<String> items) {
    if (items.size() <= 1) {
      return List.of(List.copyOf(items));
    }
    List<List<String>> out = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      List<String> rest = new ArrayList<>(items);
      String head = rest.remove(i);
      for (List<String> tail : permutations(rest)) {
        List<String> one = new ArrayList<>();
        one.add(head);
        one.addAll(tail);
        out.add(one);
      }
    }
    return out;
  }

  private static Iterable<JsonNode> optional(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? List.of() : value;
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted");
    }
  }
}
