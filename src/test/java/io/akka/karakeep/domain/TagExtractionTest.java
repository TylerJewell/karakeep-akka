package io.akka.karakeep.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 R4, R5, R7. */
class TagExtractionTest {

  @Test
  void bareFencedAndEmbeddedJsonAgree() {
    List<String> expected = List.of("Databases", "SQL");
    assertEquals(expected, TagExtraction.tagsFrom("{\"tags\":[\"Databases\",\"SQL\"]}"));
    assertEquals(
        expected,
        TagExtraction.tagsFrom(
            "Sure!\n```json\n{\"tags\":[\"Databases\",\"SQL\"]}\n```\nhope that helps"));
    assertEquals(
        expected,
        TagExtraction.tagsFrom("here you go {\"tags\":[\"Databases\",\"SQL\"]} done"));
  }

  @Test
  void aFenceWithNoLanguageIsRead() {
    assertEquals(
        List.of("Fenced"), TagExtraction.tagsFrom("```\n{\"tags\":[\"Fenced\"]}\n```"));
  }

  /**
   * The fence is read before the loose {@code {...}} span, and only a reply carrying a brace
   * before the fence tells the two apart: the loose span runs from the first brace to the last
   * and is not JSON, so a reader that skipped the fence would fail here.
   */
  @Test
  void aFenceIsPreferredToALooseSpanThatWouldSwallowIt() {
    assertEquals(
        List.of("Fenced"),
        TagExtraction.tagsFrom(
            "Note {important}: here you are\n```json\n{\"tags\":[\"Fenced\"]}\n```"));
  }

  @Test
  void unparseableAndWrongShapeBothFail() {
    assertThrows(
        TagExtraction.ModelReplyException.class,
        () -> TagExtraction.tagsFrom("I am not going to do that"));
    assertThrows(
        TagExtraction.ModelReplyException.class,
        () -> TagExtraction.tagsFrom("{\"labels\":[\"a\"]}"));
    assertThrows(
        TagExtraction.ModelReplyException.class, () -> TagExtraction.tagsFrom("{\"tags\":[1,2]}"));
  }

  @Test
  void theFailureQuotesTheStartOfTheReply() {
    var thrown =
        assertThrows(
            TagExtraction.ModelReplyException.class,
            () -> TagExtraction.tagsFrom("I am not going to do that, sorry"));
    assertEquals(true, thrown.getMessage().endsWith("I am not going to do"));
  }

  @Test
  void oneLeadingHashIsRemovedThenTrimmed() {
    assertEquals(
        List.of("Hashed", "padded", "#double"),
        TagExtraction.tagsFrom("{\"tags\":[\"#Hashed\",\"  padded  \",\"##double\"]}"));
  }

  @Test
  void anEmptyTagStaysEmptyRatherThanBeingDropped() {
    assertEquals(
        List.of("", "", "Real"), TagExtraction.tagsFrom("{\"tags\":[\"\",\"  \",\"Real\"]}"));
  }

  @Test
  void anEmptyTagListIsNotAFailure() {
    assertEquals(List.of(), TagExtraction.tagsFrom("{\"tags\":[]}"));
  }
}
