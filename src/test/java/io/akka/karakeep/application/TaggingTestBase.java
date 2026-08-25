package io.akka.karakeep.application;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.typesafe.config.ConfigFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * A runtime whose model is the scripted server rather than anything outside the machine.
 *
 * <p>The server is started before the runtime, because the workflow reads the address out of
 * configuration when it is constructed and there is no address to read until it is listening.
 */
public abstract class TaggingTestBase extends TestKitSupport {

  protected static final ScriptedModel MODEL = start();

  private static ScriptedModel start() {
    try {
      return new ScriptedModel(List.of(ScriptedModel.Reply.ok("{\"tags\":[]}")));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAclDisabled()
        .withAdditionalConfig(
            ConfigFactory.parseString(
                "karakeep.inference.base-url = \"" + MODEL.baseUrl() + "\"\n"
                    + "karakeep.inference.model = scripted\n"
                    + "karakeep.tagging.max-attempts = 4\n"));
  }

  /**
   * Runs one bookmark's tagging job to completion and reports what it settled on. The workflow
   * runs its steps after the start command has replied, so this waits for an outcome rather than
   * reading the state straight back.
   */
  protected TaggingWorkflow.Job runTagging(String bookmarkId) {
    String workflowId = "tag-" + bookmarkId + "-" + java.util.UUID.randomUUID();
    componentClient
        .forWorkflow(workflowId)
        .method(TaggingWorkflow::start)
        .invoke(new TaggingWorkflow.Start(bookmarkId));
    return await(workflowId, java.time.Duration.ofSeconds(30));
  }

  /** The workflow's state once it has settled, or a failure naming what it was still doing. */
  protected TaggingWorkflow.Job await(String workflowId, java.time.Duration limit) {
    long deadline = System.nanoTime() + limit.toNanos();
    TaggingWorkflow.Job job = null;
    while (System.nanoTime() < deadline) {
      job = componentClient.forWorkflow(workflowId).method(TaggingWorkflow::job).invoke();
      if (job != null && job.outcome() != null) {
        return job;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting for " + workflowId);
      }
    }
    throw new AssertionError("workflow " + workflowId + " did not settle: " + job);
  }

  /** The tagging status a bookmark reads at this instant. */
  protected io.akka.karakeep.domain.TaggingStatus statusOf(String bookmarkId) {
    return componentClient
        .forEventSourcedEntity(bookmarkId)
        .method(BookmarkEntity::get)
        .invoke()
        .taggingStatus();
  }
}
