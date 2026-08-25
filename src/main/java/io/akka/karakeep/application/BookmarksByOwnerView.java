package io.akka.karakeep.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.karakeep.domain.Bookmark;
import java.util.List;
import java.util.Optional;

/**
 * What an owner's bookmarks look like from outside, with their tagging status.
 *
 * <p>The row type is its own record rather than the domain's {@link Bookmark}: three of a
 * bookmark's fields are genuinely absent on most rows — a text bookmark has no URL, a link
 * bookmark has no text — and a view row field that is sometimes absent has to be {@code
 * Optional}. A plain nullable field stops the view updating on the first row that lacks it, and
 * every query against it then answers with nothing rather than with an error.
 */
@Component(id = "bookmarks-by-owner")
public class BookmarksByOwnerView extends View {

  public record BookmarkEntry(
      String bookmarkId,
      String ownerId,
      String kind,
      Optional<String> text,
      Optional<String> url,
      Optional<String> title,
      Optional<String> description,
      String taggingStatus,
      List<String> tagNames) {}

  public record Entries(List<BookmarkEntry> bookmarks) {}

  @Consume.FromEventSourcedEntity(BookmarkEntity.class)
  public static class Updater extends TableUpdater<BookmarkEntry> {

    public Effect<BookmarkEntry> onEvent(BookmarkEntity.Event event) {
      return effects().updateRow(apply(event));
    }

    private BookmarkEntry apply(BookmarkEntity.Event event) {
      return switch (event) {
        case BookmarkEntity.Ingested e ->
            new BookmarkEntry(
                updateContext().eventSubject().orElseThrow(),
                e.ownerId(),
                e.kind().name(),
                Optional.ofNullable(e.text()),
                Optional.ofNullable(e.url()),
                Optional.ofNullable(e.title()),
                Optional.ofNullable(e.description()),
                "PENDING",
                List.of());
        case BookmarkEntity.TagsApplied e -> withTags(e.attachments().stream().map(a -> a.name()).toList());
        case BookmarkEntity.TagAttachedByHand e -> {
          List<String> next = new java.util.ArrayList<>(rowState().tagNames());
          next.add(e.attachment().name());
          yield withTags(List.copyOf(next));
        }
        case BookmarkEntity.TaggingStatusSet e -> {
          BookmarkEntry row = rowState();
          yield new BookmarkEntry(
              row.bookmarkId(),
              row.ownerId(),
              row.kind(),
              row.text(),
              row.url(),
              row.title(),
              row.description(),
              e.status().name(),
              row.tagNames());
        }
      };
    }

    private BookmarkEntry withTags(List<String> tagNames) {
      BookmarkEntry row = rowState();
      return new BookmarkEntry(
          row.bookmarkId(),
          row.ownerId(),
          row.kind(),
          row.text(),
          row.url(),
          row.title(),
          row.description(),
          row.taggingStatus(),
          tagNames);
    }
  }

  @Query("SELECT * AS bookmarks FROM bookmarks_by_owner WHERE ownerId = :ownerId")
  public QueryEffect<Entries> byOwner(String ownerId) {
    return queryResult();
  }

  /** Two arguments to a query are one record; the runtime binds a query to one parameter. */
  public record OwnerAndStatus(String ownerId, String taggingStatus) {}

  @Query(
      "SELECT * AS bookmarks FROM bookmarks_by_owner"
          + " WHERE ownerId = :ownerId AND taggingStatus = :taggingStatus")
  public QueryEffect<Entries> byOwnerAndStatus(OwnerAndStatus query) {
    return queryResult();
  }
}
