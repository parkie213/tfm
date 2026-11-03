package uk.org.tfm.adapter.bods;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import uk.org.tfm.model.feed.FeedException;
import uk.org.tfm.model.vehicle.VehiclePosition;
import uk.org.tfm.model.vehicle.VehiclePositionProvider;

/**
 * Fetches and parses the BODS SIRI-VM vehicle-monitoring feed for a bounding box.
 *
 * <p>Sets explicit connect/read timeouts, never logs the API key, and validates the response is a
 * genuine SIRI document (so a truncated body or an HTML error page is rejected rather than stored
 * as a misleadingly empty snapshot).
 */
public final class SiriVmClient implements VehiclePositionProvider {

  private static final String ENDPOINT = "https://data.bus-data.dft.gov.uk/api/v1/datafeed/";

  private final HttpClient http;
  private final String apiKey;
  private final String bbox;
  private final Duration readTimeout;

  public SiriVmClient(String apiKey, String bbox) {
    this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), apiKey, bbox);
  }

  SiriVmClient(HttpClient http, String apiKey, String bbox) {
    this.apiKey = apiKey;
    this.bbox = bbox;
    this.readTimeout = Duration.ofSeconds(20);
    this.http = http;
  }

  /** Fetches the feed and returns the parsed vehicle positions. */
  @Override
  public List<VehiclePosition> fetch() throws FeedException {
    URI uri =
        URI.create(
            ENDPOINT
                + "?boundingBox="
                + URLEncoder.encode(bbox, StandardCharsets.UTF_8)
                + "&api_key="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
    // No Accept header: BODS returns 406 if one is set that doesn't match its served type.
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(readTimeout).GET().build();

    HttpResponse<byte[]> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (Exception e) {
      // Never include the URI (it carries the api_key) in the message.
      throw new FeedException(
          "SIRI-VM request failed: " + e.getClass().getSimpleName(),
          FeedException.Kind.UNAVAILABLE,
          e);
    }

    int status = response.statusCode();
    if (status != 200) {
      Optional<Duration> retryAfter =
          response.headers().firstValue("Retry-After").flatMap(SiriVmClient::parseRetryAfter);
      throw new FeedException(
          "SIRI-VM returned HTTP " + status,
          status == 429 ? FeedException.Kind.RATE_LIMITED : FeedException.Kind.UNAVAILABLE,
          retryAfter);
    }

    return SiriVmParser.parse(response.body());
  }

  private static Optional<Duration> parseRetryAfter(String value) {
    try {
      return Optional.of(Duration.ofSeconds(Long.parseLong(value.trim())));
    } catch (NumberFormatException e) {
      return Optional.empty(); // HTTP-date form not handled; caller falls back to a default
    }
  }
}
