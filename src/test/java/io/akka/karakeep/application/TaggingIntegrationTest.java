package io.akka.karakeep.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import akka.Done;
import io.akka.karakeep.domain.AttachedBy;
import io.akka.karakeep.domain.Attachment;
import io.akka.karakeep.domain.Bookmark;
import io.akka.karakeep.domain.ResolvedTag;
import io.akka.karakeep.domain.TaggingStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The tagging job, run against a real runtime. SPEC-001 R3, R9, R10, R11, R12, R13, R14, R17,
 * R20.
 */
class TaggingIntegrationTest extends TaggingTestBase {

  private int seq = 0;

  private String ingestText(String ownerId, String text) {
    String id = "bm-" + ownerId + "-" + seq++;
    componentClient
        .forEventSourcedEntity(id)
        .method(BookmarkEntity::ingestText)
        .invoke(new BookmarkEntity.IngestText(ownerId, null, text));
    return id;
  }

  private Bookmark bookmark(String id) {
    return componentClient.forEventSourcedEntity(id).method(BookmarkEntity::get).invoke();
  }

  private List<String> tagNames(String id) {
    return bookmark(id).attachments().stream().map(Attachment::name).sorted().toList();
  }

  @Test
  void aBookmarkIsTaggedFromTheModelsAnswer() {
    MODEL.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[\"Databases\",\"SQL\"]}")));
    String id = ingestText("owner-plain", "a note about databases");

    var job = runTagging(id);

    assertEquals(TaggingWorkflow.TAGGED, job.outcome());
    assertEquals(List.of("Databases", "SQL"), tagNames(id));
    assertEquals(TaggingStatus.SUCCESS, statusOf(id));
    assertEquals(1, MODEL.callCount());
  }

  @Test
  void tagsThatNormaliseAlikeAreAllCreated() {
    MODEL.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[\"Rust\",\"rust\",\"R_U_S_T\"]}")));
    String id = ingestText("owner-normalise", "a note");

    runTagging(id);

    assertEquals(List.of("R_U_S_T", "Rust", "rust"), tagNames(id));
    assertEquals(
        3,
        bookmark(id).attachments().stream().map(Attachment::tagId).distinct().count(),
        "three tags that normalise alike are three tags, not one");
  }

  @Test
  void anInferredNameMatchesATagTheOwnerAlreadyHas() {
    String owner = "owner-existing";
    componentClient
        .forEventSourcedEntity(owner)
        .method(TagCatalogEntity::resolve)
        .invoke(List.of("Machine Learning"));

    MODEL.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[\"machine-learning\"]}")));
    String id = ingestText(owner, "a note");
    runTagging(id);

    assertEquals(List.of("Machine Learning"), tagNames(id));
    assertEquals(
        1,
        componentClient.forEventSourcedEntity(owner).method(TagCatalogEntity::all).invoke().tags().size());
  }

  @Test
  void emptyTagIsStoredOnceAndAttached() {
    MODEL.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[\"\",\"  \",\"Real\"]}")));
    String id = ingestText("owner-empty", "a note");

    runTagging(id);

    assertEquals(List.of("", "Real"), tagNames(id));
  }

  @Test
  void aiTagsAreReplacedAndHumanTagsKept() {
    String owner = "owner-replace";
    String id = ingestText(owner, "a note");

    MODEL.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[\"OldAi\"]}")));
    runTagging(id);
    attachByHand(owner, id, "MineOwn");

    MODEL.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[\"NewAi\"]}")));
    runTagging(id);

    assertEquals(List.of("MineOwn", "NewAi"), tagNames(id));
    assertEquals(
        List.of(AttachedBy.HUMAN),
        bookmark(id).attachments().stream()
            .filter(a -> a.name().equals("MineOwn"))
            .map(Attachment::attachedBy)
            .toList(),
        "the tag the owner attached is still theirs");
  }

  @Test
  void anEmptyAnswerLeavesThePreviousTagsInPlace() {
    String id = ingestText("owner-nochange", "a note");

    MODEL.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[\"OldAi\"]}")));
    runTagging(id);

    MODEL.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[]}")));
    var job = runTagging(id);

    assertEquals(TaggingWorkflow.TAGGED, job.outcome());
    assertEquals(List.of("OldAi"), tagNames(id));
  }

  @Test
  void taggingTwiceWithTheSameAnswerChangesNothing() {
    String id = ingestText("owner-idempotent", "a note");
    MODEL.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[\"Stable\"]}")));

    runTagging(id);
    List<Attachment> first = bookmark(id).attachments();
    runTagging(id);

    assertEquals(first, bookmark(id).attachments());
  }

  @Test
  void aHandAttachedTagIsNotConvertedToTheModels() {
    String owner = "owner-human";
    String id = ingestText(owner, "a note");
    attachByHand(owner, id, "Rust");

    MODEL.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[\"rust\"]}")));
    runTagging(id);

    assertEquals(
        List.of(AttachedBy.HUMAN),
        bookmark(id).attachments().stream().map(Attachment::attachedBy).toList());
    assertEquals(List.of("Rust"), tagNames(id));
  }

  @Test
  void tagsAreNotSharedBetweenUsers() {
    MODEL.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[\"Shared\"]}")));
    String a = ingestText("owner-a", "one");
    String b = ingestText("owner-b", "two");

    runTagging(a);
    runTagging(b);

    String tagA = bookmark(a).attachments().getFirst().tagId();
    String tagB = bookmark(b).attachments().getFirst().tagId();
    assertNotEquals(tagA, tagB);
    assertEquals(List.of("Shared"), tagNames(a));
    assertEquals(List.of("Shared"), tagNames(b));
  }

  @Test
  void aLinkWithADescriptionReachesTheModelWithItsOwnFields() {
    MODEL.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[\"FromDescription\"]}")));
    String id = "bm-link-" + seq++;
    componentClient
        .forEventSourcedEntity(id)
        .method(BookmarkEntity::ingestLink)
        .invoke(
            new BookmarkEntity.IngestLink(
                "owner-link", "https://example.com/y", "T", "a description"));

    runTagging(id);

    assertEquals(List.of("FromDescription"), tagNames(id));
    assertEquals(
        true,
        MODEL.prompts().getFirst().contains("https://example.com/y"),
        "the prompt carries the link's own URL");
  }

  @Test
  void aLinkWithNothingToReadIsSkippedAndStillReportsSuccess() {
    MODEL.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[\"Never\"]}")));
    String id = "bm-empty-link-" + seq++;
    componentClient
        .forEventSourcedEntity(id)
        .method(BookmarkEntity::ingestLink)
        .invoke(
            new BookmarkEntity.IngestLink("owner-skip", "https://example.com/x", "A title", null));

    var job = runTagging(id);

    assertEquals(TaggingWorkflow.SKIPPED, job.outcome());
    assertEquals(TaggingStatus.SUCCESS, statusOf(id));
    assertEquals(0, MODEL.callCount());
    assertEquals(List.of(), tagNames(id));
  }

  private void attachByHand(String owner, String bookmarkId, String name) {
    ResolvedTag tag =
        componentClient
            .forEventSourcedEntity(owner)
            .method(TagCatalogEntity::resolve)
            .invoke(List.of(name))
            .tags()
            .getFirst();
    Done ignored =
        componentClient
            .forEventSourcedEntity(bookmarkId)
            .method(BookmarkEntity::attachByHand)
            .invoke(tag);
  }
}
