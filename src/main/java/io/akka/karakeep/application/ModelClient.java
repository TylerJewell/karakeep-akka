package io.akka.karakeep.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The one outbound call this port makes. SPEC-001 §4.
 *
 * <p>It speaks the chat-completions shape karakeep's own client speaks, so both systems can be
 * pointed at the same server and asked the same thing. Exactly one HTTP attempt is made per call:
 * the retrying is the workflow's, and putting a second layer of it here is what makes karakeep's
 * four attempts twelve requests.
 */
public class ModelClient {

  /** Thrown when the model could not be reached or answered with an error status. */
  public static class ModelUnavailableException extends RuntimeException {
    public ModelUnavailableException(String message) {
      super(message);
    }
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http;
  private final String endpoint;
  private final String model;

  public ModelClient(String baseUrl, String model, Duration timeout) {
    this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    this.endpoint = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
    this.model = model;
  }

  public String infer(String prompt) {
    String body;
    try {
      body =
          MAPPER.writeValueAsString(
              MAPPER
                  .createObjectNode()
                  .put("model", model)
                  .set(
                      "messages",
                      MAPPER
                          .createArrayNode()
                          .add(
                              MAPPER
                                  .createObjectNode()
                                  .put("role", "user")
                                  .put("content", prompt))));
    } catch (Exception e) {
      throw new ModelUnavailableException("could not build the request: " + e.getMessage());
    }

    HttpResponse<String> response;
    try {
      response =
          http.send(
              HttpRequest.newBuilder(URI.create(endpoint))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
    } catch (java.io.IOException e) {
      throw new ModelUnavailableException("the model could not be reached: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ModelUnavailableException("interrupted while waiting for the model");
    }

    if (response.statusCode() / 100 != 2) {
      throw new ModelUnavailableException(
          "the model answered with status " + response.statusCode());
    }

    try {
      JsonNode content =
          MAPPER.readTree(response.body()).path("choices").path(0).path("message").path("content");
      if (content.isMissingNode() || content.isNull()) {
        throw new ModelUnavailableException("the model answered with no message content");
      }
      return content.asText();
    } catch (ModelUnavailableException e) {
      throw e;
    } catch (Exception e) {
      throw new ModelUnavailableException("the model's envelope could not be read");
    }
  }
}
