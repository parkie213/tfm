package uk.org.tfm.adapter.bods;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.feed.FeedException;

class SiriVmClientTest {
  @Test
  void rateLimitCarriesRetryHintAcrossTheProviderBoundary() {
    var http = new StubHttp(429, Map.of("Retry-After", List.of("120")), new byte[0]);
    var client = new SiriVmClient(http, "secret-key", "-1.25,52.55,-0.65,52.90");
    var error = assertThrows(FeedException.class, client::fetch);
    assertEquals(FeedException.Kind.RATE_LIMITED, error.kind());
    assertEquals(Duration.ofSeconds(120), error.retryAfter().orElseThrow());
    assertFalse(error.getMessage().contains("secret-key"));
    assertTrue(
        http.request.uri().getRawQuery().contains("boundingBox=-1.25%2C52.55%2C-0.65%2C52.90"));
    assertTrue(http.request.headers().firstValue("Accept").isEmpty());
    assertEquals(Duration.ofSeconds(20), http.request.timeout().orElseThrow());
  }

  @Test
  void unavailableResponseAndMalformedRetryHintUseGenericFailure() {
    var client =
        new SiriVmClient(
            new StubHttp(503, Map.of("Retry-After", List.of("invalid")), new byte[0]),
            "secret",
            "");
    var error = assertThrows(FeedException.class, client::fetch);
    assertEquals(FeedException.Kind.UNAVAILABLE, error.kind());
    assertTrue(error.retryAfter().isEmpty());
  }

  @Test
  void networkFailureIsUnavailableWithoutExposingTheRequestUrl() {
    var http = new StubHttp(200, Map.of(), new byte[0]);
    http.failure = new IOException("https://example.invalid?api_key=secret-key");
    var client = new SiriVmClient(http, "secret-key", "");
    var error = assertThrows(FeedException.class, client::fetch);
    assertEquals(FeedException.Kind.UNAVAILABLE, error.kind());
    assertFalse(error.getMessage().contains("secret-key"));
    assertTrue(error.retryAfter().isEmpty());
  }

  @Test
  void successfulHttpResponseMustAlsoBeValidSiri() {
    var valid = "<Siri xmlns=\"http://www.siri.org.uk/siri\"><ServiceDelivery/></Siri>".getBytes();
    var client = new SiriVmClient(new StubHttp(200, Map.of(), valid), "", "");
    assertTrue(assertDoesNotThrow(client::fetch).isEmpty());
    var invalid = new SiriVmClient(new StubHttp(200, Map.of(), "<html/>".getBytes()), "", "");
    assertEquals(
        FeedException.Kind.INVALID_RESPONSE,
        assertThrows(FeedException.class, invalid::fetch).kind());
  }

  /** Captures real request construction and supplies responses without a network or API key. */
  private static final class StubHttp extends HttpClient {
    private final int status;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private HttpRequest request;
    private IOException failure;

    StubHttp(int status, Map<String, List<String>> headers, byte[] body) {
      this.status = status;
      this.headers = headers;
      this.body = body;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        throws IOException {
      if (failure != null) throw failure;
      this.request = request;
      return new HttpResponse<>() {
        public int statusCode() {
          return status;
        }

        public HttpRequest request() {
          return request;
        }

        public Optional<HttpResponse<T>> previousResponse() {
          return Optional.empty();
        }

        public HttpHeaders headers() {
          return HttpHeaders.of(headers, (a, b) -> true);
        }

        public T body() {
          return (T) body;
        }

        public Optional<SSLSession> sslSession() {
          return Optional.empty();
        }

        public URI uri() {
          return request.uri();
        }

        public HttpClient.Version version() {
          return HttpClient.Version.HTTP_1_1;
        }
      };
    }

    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    public SSLContext sslContext() {
      throw new UnsupportedOperationException();
    }

    public SSLParameters sslParameters() {
      return new SSLParameters();
    }

    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    public Version version() {
      return Version.HTTP_1_1;
    }

    public Optional<Executor> executor() {
      return Optional.empty();
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new UnsupportedOperationException();
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> pushHandler) {
      throw new UnsupportedOperationException();
    }
  }
}
