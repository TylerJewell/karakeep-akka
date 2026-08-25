package io.akka.karakeep.domain;

/** How a tag's display name becomes the name it is matched by. SPEC-001 R8. */
public final class TagName {

  private TagName() {}

  /** Lowercased, with spaces, hyphens and underscores removed. */
  public static String normalise(String displayName) {
    StringBuilder out = new StringBuilder(displayName.length());
    for (int i = 0; i < displayName.length(); i++) {
      char c = displayName.charAt(i);
      if (c == ' ' || c == '-' || c == '_') {
        continue;
      }
      out.append(Character.toLowerCase(c));
    }
    return out.toString();
  }
}
