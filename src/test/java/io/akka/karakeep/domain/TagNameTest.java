package io.akka.karakeep.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** SPEC-001 R8, R9, R10, R17. */
class TagNameTest {

  private static final Function<String, String> ids = name -> "id:" + name;

  @Test
  void spacesHyphensAndUnderscoresGoAndTheRestIsLowercased() {
    assertEquals("machinelearning", TagName.normalise("Machine Learning"));
    assertEquals("machinelearning", TagName.normalise("machine-learning"));
    assertEquals("machinelearning", TagName.normalise("MACHINE_LEARNING"));
    assertEquals("", TagName.normalise(""));
  }

  @Test
  void punctuationOtherThanThoseThreeStays() {
    assertNotEquals(TagName.normalise("c++"), TagName.normalise("c"));
  }

  @Test
  void normalisedNameMatchesExistingTag() {
    var start =
        TagCatalogue.empty().resolve(List.of("Machine Learning"), ids).catalogue();
    var again = start.resolve(List.of("machine-learning"), ids);
    assertEquals(List.of(), again.created());
    assertEquals("Machine Learning", again.resolved().getFirst().name());
  }

  @Test
  void namesThatNormaliseAlikeAndNoneExistAreAllCreated() {
    var resolution =
        TagCatalogue.empty().resolve(List.of("Rust", "rust", "R_U_S_T"), ids);
    assertEquals(3, resolution.created().size());
    assertEquals(
        List.of("Rust", "rust", "R_U_S_T"),
        resolution.resolved().stream().map(ResolvedTag::name).toList());
  }

  @Test
  void twoEmptyTagsCollapseIntoOne() {
    var resolution = TagCatalogue.empty().resolve(List.of("", "", "Real"), ids);
    assertEquals(2, resolution.created().size());
    assertEquals(
        resolution.resolved().get(0).tagId(), resolution.resolved().get(1).tagId());
  }

  @Test
  void aRepeatedNameInOneBatchResolvesToOneTag() {
    var resolution = TagCatalogue.empty().resolve(List.of("Same", "Same"), ids);
    assertEquals(1, resolution.created().size());
    assertEquals(
        resolution.resolved().get(0).tagId(), resolution.resolved().get(1).tagId());
  }
}
