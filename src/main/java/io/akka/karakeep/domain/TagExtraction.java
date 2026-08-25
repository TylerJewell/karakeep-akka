package io.akka.karakeep.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turning whatever the model said into the tags to store. SPEC-001 R4, R5, R7.
 *
 * <p>Three shapes of reply are accepted and produce the same answer: a bare JSON object, one
 * inside a markdown fence, and one embedded in prose. Anything else — including valid JSON
 * without a {@code tags} array of strings — is one failure, not two.
 */
public final class TagExtraction {

  private static final Pattern FENCED =
      Pattern.compile("```(?:json)?\\s*(\\{[\\s\\S]*?})\\s*```", Pattern.CASE_INSENSITIVE);
  private static final Pattern EMBEDDED = Pattern.compile("\\{[\\s\\S]*}");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private TagExtraction() {}

  /** Thrown when the reply cannot be read as a tag list. SPEC-001 R5. */
  public static class ModelReplyException extends RuntimeException {
    public ModelReplyException(String message) {
      super(message);
    }
  }

  public static List<String> tagsFrom(String reply) {
    String trimmed = reply == null ? "" : reply.strip();

    List<String> tags = tryObject(trimmed);
    if (tags == null) {
      Matcher fenced = FENCED.matcher(trimmed);
      if (fenced.find()) {
        tags = tryObject(fenced.group(1));
      }
    }
    if (tags == null) {
      Matcher embedded = EMBEDDED.matcher(trimmed);
      if (embedded.find()) {
        tags = tryObject(embedded.group());
      }
    }
    if (tags == null) {
      String sneakPeek = trimmed.substring(0, Math.min(20, trimmed.length()));
      throw new ModelReplyException(
          "the model did not answer with the expected JSON. Here is the start of the reply: "
              + sneakPeek);
    }

    List<String> cleaned = new ArrayList<>(tags.size());
    for (String tag : tags) {
      cleaned.add(clean(tag));
    }
    return cleaned;
  }

  /** One leading hash removed, then trimmed — in that order, so {@code ##a} keeps one. R7. */
  static String clean(String tag) {
    String out = tag.startsWith("#") ? tag.substring(1) : tag;
    return out.strip();
  }

  /**
   * Reads {@code {"tags": ["a", "b"]}} and nothing else. Returns null when the text is not that,
   * so the caller can try the next shape; the failure message is the caller's to raise.
   */
  private static List<String> tryObject(String text) {
    JsonNode node;
    try {
      node = MAPPER.readTree(text);
    } catch (Exception e) {
      return null;
    }
    if (node == null || !node.isObject()) {
      return null;
    }
    JsonNode tags = node.get("tags");
    if (tags == null || !tags.isArray()) {
      return null;
    }
    List<String> out = new ArrayList<>(tags.size());
    for (JsonNode item : tags) {
      if (!item.isTextual()) {
        return null;
      }
      out.add(item.textValue());
    }
    return out;
  }
}
