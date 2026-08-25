package io.akka.karakeep.domain;

import java.util.List;

/**
 * One bookmark's own state. SPEC-001 §2.
 *
 * <p>{@code text} is set only on a text bookmark; {@code url}, {@code title} and {@code
 * description} only on a link, and the last two are independently absent — a link may carry a
 * title and no description, which is the case R2 turns on.
 */
public record Bookmark(
    String id,
    String ownerId,
    BookmarkKind kind,
    String text,
    String url,
    String title,
    String description,
    TaggingStatus taggingStatus,
    List<Attachment> attachments) {

  public static Bookmark text(String id, String ownerId, String text) {
    return new Bookmark(
        id, ownerId, BookmarkKind.TEXT, text, null, null, null, TaggingStatus.PENDING, List.of());
  }

  public static Bookmark link(
      String id, String ownerId, String url, String title, String description) {
    return new Bookmark(
        id,
        ownerId,
        BookmarkKind.LINK,
        null,
        url,
        title,
        description,
        TaggingStatus.PENDING,
        List.of());
  }

  public Bookmark withAttachments(List<Attachment> next) {
    return new Bookmark(
        id, ownerId, kind, text, url, title, description, taggingStatus, List.copyOf(next));
  }

  public Bookmark withStatus(TaggingStatus next) {
    return new Bookmark(id, ownerId, kind, text, url, title, description, next, attachments);
  }
}
