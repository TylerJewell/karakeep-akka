package io.akka.karakeep.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import akka.http.javadsl.model.HttpResponse;
import io.akka.karakeep.application.BookmarkEntity;
import io.akka.karakeep.application.BookmarksByOwnerView;
import io.akka.karakeep.application.TagCatalogEntity;
import io.akka.karakeep.application.TaggingWorkflow;
import io.akka.karakeep.application.UserEntity;
import io.akka.karakeep.domain.Attachment;
import io.akka.karakeep.domain.Bookmark;
import java.util.List;

/**
 * The capability's own surface: ingest a bookmark, ask for it to be tagged, read what came of it.
 *
 * <p>Everything the workflow does is reachable from here, so the port can be driven the way a
 * caller would drive it rather than only from a test.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/bookmarks")
public class BookmarkEndpoint extends AbstractHttpEndpoint {

  public record IngestText(String bookmarkId, String ownerId, String title, String text) {}

  public record IngestLink(
      String bookmarkId, String ownerId, String url, String title, String description) {}

  public record AttachByHand(String name) {}

  public record BookmarkView(
      String bookmarkId,
      String ownerId,
      String kind,
      String taggingStatus,
      List<TagView> tags) {}

  public record TagView(String tagId, String name, String attachedBy) {}

  public record TaggingResult(String outcome, int attemptsMade, String lastFailure, List<String> tags) {}

  private final ComponentClient client;

  public BookmarkEndpoint(ComponentClient client) {
    this.client = client;
  }

  @Post("/text")
  public HttpResponse ingestText(IngestText cmd) {
    client
        .forEventSourcedEntity(cmd.bookmarkId())
        .method(BookmarkEntity::ingestText)
        .invoke(new BookmarkEntity.IngestText(cmd.ownerId(), cmd.title(), cmd.text()));
    return HttpResponses.created(view(cmd.bookmarkId()));
  }

  @Post("/link")
  public HttpResponse ingestLink(IngestLink cmd) {
    client
        .forEventSourcedEntity(cmd.bookmarkId())
        .method(BookmarkEntity::ingestLink)
        .invoke(
            new BookmarkEntity.IngestLink(
                cmd.ownerId(), cmd.url(), cmd.title(), cmd.description()));
    return HttpResponses.created(view(cmd.bookmarkId()));
  }

  @Get("/{bookmarkId}")
  public BookmarkView get(String bookmarkId) {
    return view(bookmarkId);
  }

  /**
   * Starts the tagging job and reports what it settled on.
   *
   * <p>The workflow runs its steps after the start command has replied, so this waits for an
   * outcome rather than reading the state straight back — a caller asking what tagging produced
   * is asking about a job that has finished.
   */
  @Post("/{bookmarkId}/tag")
  public TaggingResult tag(String bookmarkId) {
    String workflowId = "tag-" + bookmarkId + "-" + java.util.UUID.randomUUID();
    client
        .forWorkflow(workflowId)
        .method(TaggingWorkflow::start)
        .invoke(new TaggingWorkflow.Start(bookmarkId));

    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
    TaggingWorkflow.Job job = null;
    while (System.nanoTime() < deadline) {
      job = client.forWorkflow(workflowId).method(TaggingWorkflow::job).invoke();
      if (job != null && job.outcome() != null) {
        return new TaggingResult(job.outcome(), job.attemptsMade(), job.lastFailure(), job.tags());
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return new TaggingResult(null, job == null ? 0 : job.attemptsMade(), "still running", List.of());
  }

  @Post("/{bookmarkId}/tags")
  public HttpResponse attachByHand(String bookmarkId, AttachByHand cmd) {
    Bookmark bookmark =
        client.forEventSourcedEntity(bookmarkId).method(BookmarkEntity::get).invoke();
    var resolved =
        client
            .forEventSourcedEntity(bookmark.ownerId())
            .method(TagCatalogEntity::resolve)
            .invoke(List.of(cmd.name()))
            .tags()
            .getFirst();
    client
        .forEventSourcedEntity(bookmarkId)
        .method(BookmarkEntity::attachByHand)
        .invoke(resolved);
    return HttpResponses.ok(view(bookmarkId));
  }

  @Put("/owners/{ownerId}/auto-tagging")
  public String setAutoTagging(String ownerId) {
    // Read from the query string explicitly: a method parameter that is not in the path is not
    // bound to it, and a flag that silently stayed false would be invisible to every test that
    // calls the component directly.
    boolean enabled =
        requestContext()
            .queryParams()
            .getString("enabled")
            .map(Boolean::parseBoolean)
            .orElse(true);
    client
        .forKeyValueEntity(ownerId)
        .method(UserEntity::setAutoTagging)
        .invoke(enabled);
    return enabled ? "on" : "off";
  }

  @Get("/owners/{ownerId}")
  public BookmarksByOwnerView.Entries byOwner(String ownerId) {
    return client.forView().method(BookmarksByOwnerView::byOwner).invoke(ownerId);
  }

  private BookmarkView view(String bookmarkId) {
    Bookmark bookmark =
        client.forEventSourcedEntity(bookmarkId).method(BookmarkEntity::get).invoke();
    return new BookmarkView(
        bookmark.id(),
        bookmark.ownerId(),
        bookmark.kind().name(),
        bookmark.taggingStatus().name(),
        bookmark.attachments().stream()
            .map(this::tagView)
            .toList());
  }

  private TagView tagView(Attachment attachment) {
    return new TagView(attachment.tagId(), attachment.name(), attachment.attachedBy().name());
  }
}
