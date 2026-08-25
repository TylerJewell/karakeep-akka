package io.akka.karakeep.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 R1, R2, R3, R16. */
class TaggingDecisionTest {

  @Test
  void userWithAutoTaggingOffIsSkipped() {
    var bookmark = Bookmark.text("b1", "u1", "a note about databases");
    var outcome = TaggingDecision.decide(bookmark, false);
    assertInstanceOf(TaggingDecision.Skip.class, outcome);
  }

  @Test
  void autoTaggingOffOutranksEveryOtherReason() {
    // A bookmark that would otherwise be rejected is skipped instead, because the preference is
    // read first. Both readings end the job, but only one of them marks it failed.
    var noContent =
        new Bookmark(
            "b2", "u1", BookmarkKind.TEXT, null, null, null, null, TaggingStatus.PENDING, List.of());
    assertInstanceOf(TaggingDecision.Skip.class, TaggingDecision.decide(noContent, false));
  }

  @Test
  void linkWithoutDescriptionOrContentIsSkipped() {
    var bookmark = Bookmark.link("b3", "u1", "https://example.com/x", "A title", null);
    assertInstanceOf(TaggingDecision.Skip.class, TaggingDecision.decide(bookmark, true));
  }

  @Test
  void anEmptyDescriptionIsTheSameAsNoDescription() {
    var bookmark = Bookmark.link("b4", "u1", "https://example.com/x", "A title", "");
    assertInstanceOf(TaggingDecision.Skip.class, TaggingDecision.decide(bookmark, true));
  }

  @Test
  void linkPromptCarriesUrlTitleAndDescription() {
    var bookmark =
        Bookmark.link("b5", "u1", "https://example.com/y", "T", "a description");
    var outcome = TaggingDecision.decide(bookmark, true);
    var ask = assertInstanceOf(TaggingDecision.Ask.class, outcome);
    assertTrue(ask.prompt().contains("https://example.com/y"));
    assertTrue(ask.prompt().contains("Title: T"));
    assertTrue(ask.prompt().contains("Description: a description"));
  }

  @Test
  void textPromptCarriesTheNote() {
    var outcome = TaggingDecision.decide(Bookmark.text("b6", "u1", "a note"), true);
    var ask = assertInstanceOf(TaggingDecision.Ask.class, outcome);
    assertTrue(ask.prompt().endsWith("a note"));
  }

  @Test
  void bookmarkWithNoContentIsAnError() {
    var noContent =
        new Bookmark(
            "b7", "u1", BookmarkKind.TEXT, null, null, null, null, TaggingStatus.PENDING, List.of());
    var reject = assertInstanceOf(TaggingDecision.Reject.class, TaggingDecision.decide(noContent, true));
    assertEquals("unsupported bookmark type", reject.because());
  }
}
