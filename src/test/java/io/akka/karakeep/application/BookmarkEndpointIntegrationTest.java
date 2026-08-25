package io.akka.karakeep.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.akka.karakeep.api.BookmarkEndpoint;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The capability driven the way a caller drives it, over HTTP.
 *
 * <p>The auto-tagging switch is set through a query parameter rather than a body, because a
 * parameter that is not in the route is not bound from the query string by itself, and a test
 * that calls the component directly cannot tell whether the endpoint read it.
 */
class BookmarkEndpointIntegrationTest extends TaggingTestBase {

  @Test
  void aBookmarkIsIngestedTaggedAndReadBack() {
    MODEL.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[\"Databases\"]}")));

    var created =
        httpClient
            .POST("/bookmarks/text")
            .withRequestBody(new BookmarkEndpoint.IngestText("http-1", "owner-http", "A note", "a note"))
            .responseBodyAs(BookmarkEndpoint.BookmarkView.class)
            .invoke()
            .body();
    assertEquals("PENDING", created.taggingStatus());

    var result =
        httpClient
            .POST("/bookmarks/http-1/tag")
            .responseBodyAs(BookmarkEndpoint.TaggingResult.class)
            .invoke()
            .body();
    assertEquals(TaggingWorkflow.TAGGED, result.outcome());

    var read =
        httpClient
            .GET("/bookmarks/http-1")
            .responseBodyAs(BookmarkEndpoint.BookmarkView.class)
            .invoke()
            .body();
    assertEquals("SUCCESS", read.taggingStatus());
    assertEquals(List.of("Databases"), read.tags().stream().map(BookmarkEndpoint.TagView::name).toList());
  }

  @Test
  void theAutoTaggingSwitchIsReadFromTheQueryString() {
    MODEL.reset(List.of(ScriptedModel.Reply.ok("{\"tags\":[\"Never\"]}")));

    String answer =
        httpClient
            .PUT("/bookmarks/owners/owner-http-off/auto-tagging?enabled=false")
            .responseBodyAs(String.class)
            .invoke()
            .body();
    assertEquals("off", answer);

    httpClient
        .POST("/bookmarks/text")
        .withRequestBody(new BookmarkEndpoint.IngestText("http-2", "owner-http-off", "A note", "a note"))
        .invoke();

    var result =
        httpClient
            .POST("/bookmarks/http-2/tag")
            .responseBodyAs(BookmarkEndpoint.TaggingResult.class)
            .invoke()
            .body();

    assertEquals(TaggingWorkflow.SKIPPED, result.outcome());
    assertEquals(0, MODEL.callCount(), "the switch reached the workflow, so the model was not called");
  }
}
