package io.akka.karakeep.domain;

/**
 * Whether to ask the model about this bookmark, and what to ask it. SPEC-001 R1, R2, R3, R16.
 *
 * <p>The three outcomes are kept apart because they end differently: a skip completes the job, an
 * error fails it, and only an ask reaches the model. Reading the source, they are one function
 * returning null, throwing, or returning a prompt.
 */
public final class TaggingDecision {

  private TaggingDecision() {}

  public sealed interface Outcome {}

  /** Nothing to ask about. The job completes without tagging. R1, R2. */
  public record Skip(String because) implements Outcome {}

  /** The prompt to send. R3. */
  public record Ask(String prompt) implements Outcome {}

  /** The bookmark has no content of any kind. R16. */
  public record Reject(String because) implements Outcome {}

  public static Outcome decide(Bookmark bookmark, boolean autoTaggingEnabled) {
    if (!autoTaggingEnabled) {
      return new Skip("the owner has auto-tagging turned off");
    }
    return switch (bookmark.kind()) {
      case TEXT -> {
        if (bookmark.text() == null) {
          yield new Reject("unsupported bookmark type");
        }
        yield new Ask(prompt(bookmark.text()));
      }
      case LINK -> {
        if (bookmark.url() == null) {
          yield new Reject("unsupported bookmark type");
        }
        boolean nothingToRead =
            bookmark.description() == null || bookmark.description().isEmpty();
        if (nothingToRead) {
          yield new Skip("the link has neither a description nor content");
        }
        yield new Ask(
            prompt(
                "URL: "
                    + bookmark.url()
                    + "\nTitle: "
                    + orEmpty(bookmark.title())
                    + "\nDescription: "
                    + orEmpty(bookmark.description())
                    + "\nContent: "));
      }
    };
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  /**
   * The instruction around the bookmark's own text. Its wording is this port's, not the source's
   * — see ACKNOWLEDGEMENTS.md — and neither system's benchmark sends it to a real model, so what
   * is compared is which bookmark fields reach the prompt, not how the request is phrased.
   */
  private static String prompt(String content) {
    return """
        You are given a bookmark. Reply with a JSON object of the form \
        {"tags": ["tag one", "tag two"]} and nothing else.

        """
        + content;
  }
}
