package io.akka.karakeep.api;

import akka.NotUsed;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import akka.stream.javadsl.Source;
import io.akka.karakeep.application.BookmarksByOwnerView;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * One owner's bookmarks and the tags the model gave them, as a page.
 *
 * <p>This is the screen RENDERING.md R7 puts in the slice: karakeep's own public list page
 * shows a bookmark's tags, so a person watching it sees this slice's state change, and a port
 * of the tagging capability owes a screen showing the same thing. What karakeep's page is not
 * is reusable — see gui/manifest.json — so this one is written here and compared against a
 * baseline captured from the original running, with the difference declared rather than
 * argued away.
 *
 * <p>R1: the page holds one event stream open and makes no repeated request for data. The
 * first frame arrives immediately, so the first render needs no second round trip, and a
 * frame is sent only when the read side has actually moved.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/public/lists")
public class PublicListEndpoint extends AbstractHttpEndpoint {

  /** What one bookmark looks like on the page. */
  public record Card(String bookmarkId, String kind, String title, String body, String host,
      List<String> tags, String taggingStatus) {}

  public record Listing(String ownerId, String name, String description, int numItems,
      List<Card> bookmarks) {}

  // Small enough that R1.2's 250 ms write-to-render budget is met with room to spare.
  private static final Duration TICK = Duration.ofMillis(100);

  private final ComponentClient client;

  public PublicListEndpoint(ComponentClient client) {
    this.client = client;
  }

  @Get("/{ownerId}")
  public HttpResponse page(String ownerId) {
    String html = PAGE.replace("__OWNER__", ownerId);
    // Built directly rather than through HttpResponses, whose ok(String) answers text/plain
    // and would have the browser show the markup rather than render it.
    return HttpResponse.create()
        .withStatus(StatusCodes.OK)
        .withEntity(ContentTypes.TEXT_HTML_UTF8, html.getBytes(StandardCharsets.UTF_8));
  }

  @Get("/{ownerId}/stream")
  public HttpResponse stream(String ownerId) {
    Source<Listing, NotUsed> updates =
        Source.tick(Duration.ZERO, TICK, "tick")
            .map(ignored -> listing(ownerId))
            .statefulMapConcat(
                () -> {
                  final Listing[] last = new Listing[1];
                  return next -> {
                    if (last[0] != null && last[0].equals(next)) {
                      return List.of();
                    }
                    last[0] = next;
                    return List.of(next);
                  };
                })
            .mapMaterializedValue(m -> NotUsed.getInstance());
    return HttpResponses.serverSentEvents(updates);
  }

  private Listing listing(String ownerId) {
    List<BookmarksByOwnerView.BookmarkEntry> rows =
        client.forView().method(BookmarksByOwnerView::byOwner).invoke(ownerId).bookmarks();
    List<Card> cards =
        rows.stream()
            .sorted(java.util.Comparator.comparing(BookmarksByOwnerView.BookmarkEntry::bookmarkId))
            .map(
                r ->
                    new Card(
                        r.bookmarkId(),
                        r.kind(),
                        r.title().orElse(r.url().orElse("")),
                        r.text().orElse(r.description().orElse("")),
                        r.url().map(PublicListEndpoint::host).orElse(""),
                        r.tagNames(),
                        r.taggingStatus()))
            .toList();
    return new Listing(ownerId, "Reading", "What the tagger made of these", cards.size(), cards);
  }

  private static String host(String url) {
    try {
      return java.net.URI.create(url).getHost();
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  /**
   * The page. It is one file rather than a build because there is nothing to build: it
   * subscribes to the stream above and writes the frame into the document.
   */
  private static final String PAGE =
      """
      <!DOCTYPE html>
      <html lang="en">
      <head>
      <meta charset="utf-8">
      <title>Reading - karakeep-akka</title>
      <style>
        :root { color-scheme: light; }
        body { margin: 0; background: #eef1f6; color: #0b0f19;
               font-family: Inter, system-ui, -apple-system, "Segoe UI", sans-serif; }
        main { max-width: 1216px; margin: 12px auto 0; }
        header { background: linear-gradient(90deg, #f2f0f7, #eceff5);
                 border: 1px solid #e6e8ee; border-radius: 10px; padding: 20px 28px 14px; }
        .wordmark { font-weight: 700; font-size: 22px; letter-spacing: -0.02em; }
        .titleline { display: flex; align-items: center; gap: 14px; margin-top: 22px; }
        h1 { font-size: 34px; margin: 0; letter-spacing: -0.02em; }
        .icon { font-size: 30px; }
        .description { color: #6b7280; font-size: 17px; margin: 8px 0 0 66px; }
        .count { text-align: right; color: #6b7280; font-size: 11px;
                 letter-spacing: 0.08em; margin-top: 26px; }
        .grid { display: flex; align-items: flex-start; gap: 18px; margin-top: 16px; }
        .card { background: #fff; border: 1px solid #e6e8ee; border-radius: 10px;
                padding: 14px 18px 12px; width: 360px; }
        .card h2 { font-size: 18px; font-weight: 500; margin: 0 0 8px;
                   line-height: 1.15; letter-spacing: -0.01em; }
        .body { font-size: 15px; line-height: 1.55; margin: 0 0 10px; }
        .tags { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
        .tag { background: #eef1f6; color: #374151; border-radius: 999px;
               padding: 3px 9px; font-size: 12px; font-weight: 300; }
        .footer { color: #9a3412; font-size: 12px; }
        .footer .host { color: #6b7280; }
      </style>
      </head>
      <body>
      <main>
        <header>
          <div class="wordmark">karakeep-akka</div>
          <div class="titleline"><span class="icon">&#128218;</span><h1 id="name"></h1></div>
          <div class="description" id="description"></div>
          <div class="count" id="count"></div>
        </header>
        <div class="grid" id="grid"></div>
      </main>
      <script>
        const owner = "__OWNER__";
        const grid = document.getElementById("grid");

        function render(listing) {
          document.getElementById("name").textContent = listing.name;
          document.getElementById("description").textContent = listing.description;
          document.getElementById("count").textContent =
            listing.numItems + (listing.numItems === 1 ? " BOOKMARK" : " BOOKMARKS");
          grid.replaceChildren();
          for (const card of listing.bookmarks) {
            const el = document.createElement("div");
            el.className = "card";
            const title = document.createElement("h2");
            title.textContent = card.title;
            el.appendChild(title);
            if (card.body) {
              const body = document.createElement("p");
              body.className = "body";
              body.textContent = card.body;
              el.appendChild(body);
            }
            const tags = document.createElement("div");
            tags.className = "tags";
            for (const tag of card.tags) {
              const pill = document.createElement("span");
              pill.className = "tag";
              pill.textContent = tag;
              tags.appendChild(pill);
            }
            el.appendChild(tags);
            const footer = document.createElement("div");
            footer.className = "footer";
            if (card.host) {
              const host = document.createElement("span");
              host.className = "host";
              host.textContent = card.host + "  \\u2022  ";
              footer.appendChild(host);
            }
            footer.appendChild(document.createTextNode(card.taggingStatus.toLowerCase()));
            el.appendChild(footer);
            grid.appendChild(el);
          }
        }

        // One connection, held open. Nothing here asks again on a timer; EventSource
        // reconnects by itself when the connection is cut, and the next frame is a whole
        // listing rather than a delta, so the page converges without replaying anything.
        const stream = new EventSource("/public/lists/" + owner + "/stream");
        stream.onmessage = (event) => render(JSON.parse(event.data));
      </script>
      </body>
      </html>
      """;
}
