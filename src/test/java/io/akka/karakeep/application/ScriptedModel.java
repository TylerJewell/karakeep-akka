package io.akka.karakeep.application;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A model that answers whatever it was told to answer, over HTTP.
 *
 * <p>This is the same stand-in the source probes use, at the same place: the network. Both systems
 * are pointed at a server of this shape, so a difference in what they store is a difference in
 * what they do with the answer rather than in what they were answered.
 */
public final class ScriptedModel implements AutoCloseable {

  public record Reply(int status, String content) {

    public static Reply ok(String content) {
      return new Reply(200, content);
    }

    public static Reply unavailable() {
      return new Reply(500, null);
    }
  }

  private final HttpServer server;
  private final AtomicInteger served = new AtomicInteger();
  private final List<String> prompts = java.util.Collections.synchronizedList(new ArrayList<>());
  private volatile List<Reply> script;
  /** Called on every request, before the reply is written. */
  private volatile Runnable onRequest = () -> {};

  public ScriptedModel(List<Reply> script) throws IOException {
    this.script = List.copyOf(script);
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    this.server.createContext(
        "/v1/chat/completions",
        exchange -> {
          byte[] request = exchange.getRequestBody().readAllBytes();
          prompts.add(new String(request, StandardCharsets.UTF_8));
          onRequest.run();
          Reply reply = this.script.get(Math.min(served.getAndIncrement(), this.script.size() - 1));
          byte[] body =
              (reply.status() == 200
                      ? envelope(reply.content())
                      : "{\"error\":{\"message\":\"the model is down\"}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(reply.status(), body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    this.server.start();
  }

  private static String envelope(String content) {
    String escaped =
        content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n");
    return "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
        + escaped
        + "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"total_tokens\":7}}";
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  public int callCount() {
    return served.get();
  }

  public List<String> prompts() {
    return List.copyOf(prompts);
  }

  /** Runs on every request, so a caller can read the system's state mid-flight. */
  public void onRequest(Runnable action) {
    this.onRequest = action;
  }

  public void reset(List<Reply> next) {
    this.onRequest = () -> {};
    this.script = List.copyOf(next);
    served.set(0);
    prompts.clear();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
