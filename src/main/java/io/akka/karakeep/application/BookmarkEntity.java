package io.akka.karakeep.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.akka.karakeep.domain.AttachedBy;
import io.akka.karakeep.domain.Attachment;
import io.akka.karakeep.domain.Bookmark;
import io.akka.karakeep.domain.BookmarkKind;
import io.akka.karakeep.domain.ResolvedTag;
import io.akka.karakeep.domain.TagApplication;
import io.akka.karakeep.domain.TaggingStatus;
import java.util.ArrayList;
import java.util.List;

/** One bookmark. SPEC-001 §2, R11–R14, R15, R19, R20, R21. */
@Component(id = "bookmark")
public class BookmarkEntity extends EventSourcedEntity<Bookmark, BookmarkEntity.Event> {

  public sealed interface Event {}

  @TypeName("bookmark-ingested")
  public record Ingested(
      String ownerId,
      BookmarkKind kind,
      String text,
      String url,
      String title,
      String description)
      implements Event {}

  @TypeName("bookmark-tags-applied")
  public record TagsApplied(List<Attachment> attachments) implements Event {}

  @TypeName("bookmark-tag-attached-by-hand")
  public record TagAttachedByHand(Attachment attachment) implements Event {}

  @TypeName("bookmark-tagging-status-set")
  public record TaggingStatusSet(TaggingStatus status) implements Event {}

  /**
   * @param title what a screen shows as the bookmark's heading. Not part of any rule in
   *     SPEC-001; a text bookmark's tags are inferred from its text, not its title.
   */
  public record IngestText(String ownerId, String title, String text) {}

  public record IngestLink(String ownerId, String url, String title, String description) {}

  private final String bookmarkId;

  public BookmarkEntity(EventSourcedEntityContext context) {
    this.bookmarkId = context.entityId();
  }

  public Effect<Done> ingestText(IngestText cmd) {
    if (currentState() != null) {
      return effects().error("bookmark " + bookmarkId + " already exists");
    }
    return effects()
        .persist(
            new Ingested(
                cmd.ownerId(), BookmarkKind.TEXT, cmd.text(), null, cmd.title(), null))
        .thenReply(unused -> Done.getInstance());
  }

  public Effect<Done> ingestLink(IngestLink cmd) {
    if (currentState() != null) {
      return effects().error("bookmark " + bookmarkId + " already exists");
    }
    return effects()
        .persist(
            new Ingested(
                cmd.ownerId(),
                BookmarkKind.LINK,
                null,
                cmd.url(),
                cmd.title(),
                cmd.description()))
        .thenReply(unused -> Done.getInstance());
  }

  /** R15 — a bookmark that was never ingested is an error rather than an empty answer. */
  public ReadOnlyEffect<Bookmark> get() {
    if (currentState() == null) {
      return effects().error("bookmark with id " + bookmarkId + " was not found");
    }
    return effects().reply(currentState());
  }

  public Effect<Done> attachByHand(ResolvedTag tag) {
    if (currentState() == null) {
      return effects().error("bookmark with id " + bookmarkId + " was not found");
    }
    boolean already =
        currentState().attachments().stream().anyMatch(a -> a.tagId().equals(tag.tagId()));
    if (already) {
      return effects().reply(Done.getInstance());
    }
    return effects()
        .persist(
            new TagAttachedByHand(
                new Attachment(tag.tagId(), tag.name(), AttachedBy.HUMAN)))
        .thenReply(unused -> Done.getInstance());
  }

  /** R11–R14. The decision is in {@link TagApplication}; this is where it is recorded. */
  public Effect<Done> applyInferredTags(List<ResolvedTag> inferred) {
    if (currentState() == null) {
      return effects().error("bookmark with id " + bookmarkId + " was not found");
    }
    List<Attachment> next = TagApplication.apply(currentState().attachments(), inferred);
    if (next.equals(currentState().attachments())) {
      return effects().reply(Done.getInstance());
    }
    return effects().persist(new TagsApplied(next)).thenReply(unused -> Done.getInstance());
  }

  public Effect<Done> setTaggingStatus(TaggingStatus status) {
    if (currentState() == null) {
      return effects().error("bookmark with id " + bookmarkId + " was not found");
    }
    if (currentState().taggingStatus() == status) {
      return effects().reply(Done.getInstance());
    }
    return effects()
        .persist(new TaggingStatusSet(status))
        .thenReply(unused -> Done.getInstance());
  }

  @Override
  public Bookmark applyEvent(Event event) {
    return switch (event) {
      case Ingested e ->
          new Bookmark(
              bookmarkId,
              e.ownerId(),
              e.kind(),
              e.text(),
              e.url(),
              e.title(),
              e.description(),
              TaggingStatus.PENDING,
              List.of());
      case TagsApplied e -> currentState().withAttachments(e.attachments());
      case TagAttachedByHand e -> {
        List<Attachment> next = new ArrayList<>(currentState().attachments());
        next.add(e.attachment());
        yield currentState().withAttachments(next);
      }
      case TaggingStatusSet e -> currentState().withStatus(e.status());
    };
  }
}
