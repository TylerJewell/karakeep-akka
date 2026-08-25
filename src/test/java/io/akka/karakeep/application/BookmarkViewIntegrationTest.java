package io.akka.karakeep.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The read side, including the fields that are absent on most rows.
 *
 * <p>A text bookmark has no URL and a link bookmark has no text, so both kinds have to be in the
 * view at once for this to say anything: a row field that is sometimes absent stops the view
 * updating unless it is {@code Optional}, and the symptom is an empty answer rather than an error.
 */
class BookmarkViewIntegrationTest extends TaggingTestBase {

  @Test
  void bothKindsOfBookmarkReachTheViewWithTheirTagsAndStatus() {
    String owner = "view-owner";
    MODEL.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[\"Databases\"]}")));

    componentClient
        .forEventSourcedEntity("view-text")
        .method(BookmarkEntity::ingestText)
        .invoke(new BookmarkEntity.IngestText(owner, "A note about databases", "a note about databases"));
    componentClient
        .forEventSourcedEntity("view-link")
        .method(BookmarkEntity::ingestLink)
        .invoke(
            new BookmarkEntity.IngestLink(
                owner, "https://example.com/z", "A title", "a description"));

    runTagging("view-text");
    runTagging("view-link");

    var rows = awaitRows(owner, 2, "SUCCESS");
    var text = rows.stream().filter(r -> r.bookmarkId().equals("view-text")).findFirst().orElseThrow();
    var link = rows.stream().filter(r -> r.bookmarkId().equals("view-link")).findFirst().orElseThrow();

    assertEquals(Optional.empty(), text.url(), "a text bookmark has no URL");
    assertEquals(Optional.empty(), link.text(), "a link bookmark has no text");
    assertEquals(Optional.of("https://example.com/z"), link.url());
    assertEquals("SUCCESS", text.taggingStatus());
    assertEquals(List.of("Databases"), text.tagNames());
  }

  @Test
  void aFailedBookmarkIsFoundByItsStatus() {
    String owner = "view-owner-failed";
    MODEL.reset(List.of(ScriptedModel.Reply.unavailable()));
    componentClient
        .forEventSourcedEntity("view-failed")
        .method(BookmarkEntity::ingestText)
        .invoke(new BookmarkEntity.IngestText(owner, null, "a note"));

    runTagging("view-failed");

    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    List<BookmarksByOwnerView.BookmarkEntry> failed = List.of();
    while (System.nanoTime() < deadline && failed.isEmpty()) {
      failed =
          componentClient
              .forView()
              .method(BookmarksByOwnerView::byOwnerAndStatus)
              .invoke(new BookmarksByOwnerView.OwnerAndStatus(owner, "FAILURE"))
              .bookmarks();
      sleep();
    }
    assertEquals(1, failed.size());
    assertEquals("view-failed", failed.getFirst().bookmarkId());
    assertTrue(failed.getFirst().tagNames().isEmpty());
  }

  /**
   * The view is fed from the entity's events rather than written to directly, so a row can be
   * present before the last event about it has arrived. Waiting for the row count alone reads a
   * half-updated row and reports it as a difference in the capability.
   */
  private List<BookmarksByOwnerView.BookmarkEntry> awaitRows(String owner, int expected, String status) {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    List<BookmarksByOwnerView.BookmarkEntry> rows = List.of();
    while (System.nanoTime() < deadline) {
      rows = componentClient.forView().method(BookmarksByOwnerView::byOwner).invoke(owner).bookmarks();
      if (rows.size() >= expected && rows.stream().allMatch(r -> r.taggingStatus().equals(status))) {
        return rows;
      }
      sleep();
    }
    throw new AssertionError("the view held " + rows.size() + " rows: " + rows);
  }

  private static void sleep() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
