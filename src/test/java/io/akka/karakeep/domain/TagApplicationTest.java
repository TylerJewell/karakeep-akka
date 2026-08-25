package io.akka.karakeep.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 R11–R14. */
class TagApplicationTest {

  private static ResolvedTag tag(String id, String name) {
    return new ResolvedTag(id, name, TagName.normalise(name));
  }

  @Test
  void aiTagsAreReplacedAndHumanTagsKept() {
    List<Attachment> before =
        List.of(
            new Attachment("t1", "OldAi", AttachedBy.AI),
            new Attachment("t2", "MineOwn", AttachedBy.HUMAN));
    List<Attachment> after = TagApplication.apply(before, List.of(tag("t3", "NewAi")));
    assertEquals(
        List.of(
            new Attachment("t2", "MineOwn", AttachedBy.HUMAN),
            new Attachment("t3", "NewAi", AttachedBy.AI)),
        after);
  }

  @Test
  void emptyInferredSetChangesNothing() {
    List<Attachment> before = List.of(new Attachment("t1", "OldAi", AttachedBy.AI));
    assertEquals(before, TagApplication.apply(before, List.of()));
  }

  @Test
  void applyingTheSameTagsTwiceIsIdempotent() {
    List<ResolvedTag> inferred = List.of(tag("t1", "Stable"));
    List<Attachment> once = TagApplication.apply(List.of(), inferred);
    List<Attachment> twice = TagApplication.apply(once, inferred);
    assertEquals(List.of(new Attachment("t1", "Stable", AttachedBy.AI)), once);
    assertEquals(once, twice);
  }

  @Test
  void humanAttachmentSurvivesTheSameInferredTag() {
    List<Attachment> before = List.of(new Attachment("t1", "Rust", AttachedBy.HUMAN));
    List<Attachment> after = TagApplication.apply(before, List.of(tag("t1", "Rust")));
    assertEquals(before, after);
  }

  @Test
  void severalInferredTagsAreAttachedInTheOrderTheyWereInferred() {
    List<Attachment> after =
        TagApplication.apply(List.of(), List.of(tag("t1", "One"), tag("t2", "Two")));
    assertEquals(
        List.of(
            new Attachment("t1", "One", AttachedBy.AI),
            new Attachment("t2", "Two", AttachedBy.AI)),
        after);
  }
}
