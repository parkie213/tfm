package uk.org.tfm.adapter.darwin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import uk.org.tfm.model.board.Board;
import uk.org.tfm.model.board.BoardProvider;
import uk.org.tfm.model.feed.FeedHealth;

/**
 * Client for the Rail Data Marketplace LDBWS REST API (Darwin live boards).
 *
 * <p>Calls e.g. {@code GET {baseUrl}/GetDepBoardWithDetails/{CRS}?numRows=N} with the consumer key
 * in the {@code x-apikey} header, and returns a provider-independent board. Departures and arrivals
 * are separate RDM products, so each has its own base URL + key (arrivals may be unconfigured).
 */
public class DarwinClient implements BoardProvider {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  // RDM example URLs often include the operation; we append it ourselves, so strip it if present.
  private static final Set<String> OPERATIONS =
      Set.of(
          "GetDepBoardWithDetails",
          "GetArrBoardWithDetails",
          "GetArrivalDepartureBoard",
          "GetDepartureBoard",
          "GetArrivalBoard",
          "GetServiceDetails");

  private final HttpClient http;
  private final String depBaseUrl;
  private final String depApiKey;
  private final String arrBaseUrl;
  private final String arrApiKey;
  private final Duration timeout;

  // Health per product, for the data-sources page: departures and arrivals are separate RDM
  // subscriptions, so a failing arrivals key must not mark the departure boards as broken.
  private final Health departuresHealth;
  private final Health arrivalsHealth;

  public DarwinClient(
      String depBaseUrl, String depApiKey, String arrBaseUrl, String arrApiKey, int timeoutMs) {
    this.depBaseUrl = normalizeBase(depBaseUrl);
    this.depApiKey = depApiKey;
    this.arrBaseUrl = normalizeBase(arrBaseUrl);
    this.arrApiKey = arrApiKey;
    this.timeout = Duration.ofMillis(timeoutMs);
    this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    this.departuresHealth = new Health(!this.depBaseUrl.isBlank() && !isBlank(depApiKey));
    this.arrivalsHealth = new Health(!this.arrBaseUrl.isBlank());
  }

  /** Returns the departure board for a CRS station code (e.g. "MMO"). */
  public Board getDepartureBoard(String crs, int numRows) {
    return board(
        depBaseUrl, depApiKey, "GetDepBoardWithDetails", crs, numRows, false, departuresHealth);
  }

  /** Returns the arrival board. Requires the arrivals product to be configured. */
  public Board getArrivalBoard(String crs, int numRows) {
    if (arrBaseUrl.isBlank()) {
      throw new IllegalStateException("Arrivals are not enabled (DARWIN_ARRIVALS_BASE_URL unset)");
    }
    return board(
        arrBaseUrl, arrApiKey, "GetArrBoardWithDetails", crs, numRows, true, arrivalsHealth);
  }

  private Board board(
      String base,
      String apiKey,
      String operation,
      String crs,
      int numRows,
      boolean arrivals,
      Health health) {
    URI uri = URI.create(base + "/" + operation + "/" + crs + "?numRows=" + numRows);
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("x-apikey", apiKey)
            .header("Accept", "application/json")
            .GET()
            .build();

    try {
      HttpResponse<String> response;
      try {
        response = http.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (Exception e) {
        throw new RuntimeException("LDBWS request failed: " + e.getMessage(), e);
      }
      if (response.statusCode() != 200) {
        throw new RuntimeException(
            "LDBWS returned HTTP " + response.statusCode() + ": " + snippet(response.body()));
      }
      JsonNode tree;
      try {
        tree = MAPPER.readTree(response.body());
      } catch (Exception e) {
        throw new RuntimeException("Failed to parse LDBWS JSON: " + e.getMessage(), e);
      }
      Board parsed = DarwinBoardParser.parse(tree, arrivals);
      health.lastSuccessAt = Instant.now();
      return parsed;
    } catch (RuntimeException e) {
      health.lastErrorAt = Instant.now();
      health.lastError = e.getMessage();
      throw e;
    }
  }

  // ---- Feed health ----

  /** Health of the departure-board product (LDBWS). */
  public FeedHealth departuresHealth() {
    return departuresHealth;
  }

  /** Health of the arrivals product; {@code configured()} is false when it isn't subscribed. */
  public FeedHealth arrivalsHealth() {
    return arrivalsHealth;
  }

  /** Outcome of the most recent call to one product. Written on call threads, read on describe. */
  private static final class Health implements FeedHealth {
    private final boolean configured;
    private volatile Instant lastSuccessAt;
    private volatile Instant lastErrorAt;
    private volatile String lastError;

    Health(boolean configured) {
      this.configured = configured;
    }

    @Override
    public boolean configured() {
      return configured;
    }

    @Override
    public Optional<Instant> lastSuccessAt() {
      return Optional.ofNullable(lastSuccessAt);
    }

    @Override
    public Optional<Instant> lastErrorAt() {
      return Optional.ofNullable(lastErrorAt);
    }

    @Override
    public Optional<String> lastError() {
      return Optional.ofNullable(lastError);
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /** Trims a trailing slash and a trailing operation segment (RDM example URLs include one). */
  private static String normalizeBase(String url) {
    if (url == null || url.isBlank()) {
      return "";
    }
    String u = url.trim();
    while (u.endsWith("/")) {
      u = u.substring(0, u.length() - 1);
    }
    int slash = u.lastIndexOf('/');
    if (slash > 0 && OPERATIONS.contains(u.substring(slash + 1))) {
      u = u.substring(0, slash);
    }
    return u;
  }

  private static String snippet(String body) {
    if (body == null || body.isBlank()) {
      return "(empty body)";
    }
    return body.length() > 200 ? body.substring(0, 200) + "…" : body;
  }
}
