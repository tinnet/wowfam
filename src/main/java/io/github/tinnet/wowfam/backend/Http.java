package io.github.tinnet.wowfam.backend;

import static java.util.function.Predicate.not;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the shared {@link HttpClient}, honouring {@code HTTPS_PROXY}, {@code HTTP_PROXY} and
 * {@code NO_PROXY} (either case) like curl and most CLIs. Java's {@code -Dhttps.proxyHost} wins
 * when set.
 */
public final class Http {

  private Http() {}

  public static HttpClient client(Map<String, String> env) {
    var builder =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20));
    if (System.getProperty("https.proxyHost") != null
        || System.getProperty("http.proxyHost") != null) {
      builder.proxy(ProxySelector.getDefault());
    } else {
      EnvProxySelector.from(env).ifPresent(builder::proxy);
    }
    return builder.build();
  }

  /** Proxy selection from the conventional environment variables. */
  public static final class EnvProxySelector extends ProxySelector {

    private final Optional<Proxy> https;
    private final Optional<Proxy> http;
    private final List<String> noProxy;

    EnvProxySelector(Optional<Proxy> https, Optional<Proxy> http, List<String> noProxy) {
      this.https = https;
      this.http = http;
      this.noProxy = noProxy;
    }

    public static Optional<ProxySelector> from(Map<String, String> env) {
      var https = proxy(get(env, "HTTPS_PROXY"));
      var http = proxy(get(env, "HTTP_PROXY"));
      if (https.isEmpty() && http.isEmpty()) {
        return Optional.empty();
      }
      var noProxy =
          get(env, "NO_PROXY")
              .map(v ->
                  Arrays.stream(v.split(","))
                      .map(s -> s.strip().toLowerCase(Locale.ROOT))
                      .filter(not(String::isEmpty))
                      .toList())
              .orElseGet(List::of);
      return Optional.of(new EnvProxySelector(https, http, noProxy));
    }

    @Override
    public List<Proxy> select(URI uri) {
      var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
      if (bypass(host)) {
        return List.of(Proxy.NO_PROXY);
      }
      var proxy = "https".equalsIgnoreCase(uri.getScheme()) ? https : http;
      return List.of(proxy.orElse(Proxy.NO_PROXY));
    }

    boolean bypass(String host) {
      for (var entry : noProxy) {
        if (entry.equals("*")) {
          return true;
        }
        var domain = entry.startsWith("*.") ? entry.substring(1) : entry;
        if (domain.startsWith(".")
            ? host.endsWith(domain) || host.equals(domain.substring(1))
            : host.equals(domain) || host.endsWith("." + domain)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
      // nothing to fall back to
    }

    private static Optional<String> get(Map<String, String> env, String name) {
      return Optional.ofNullable(env.get(name))
          .or(() -> Optional.ofNullable(env.get(name.toLowerCase(Locale.ROOT))))
          .filter(v -> !v.isBlank());
    }

    private static Optional<Proxy> proxy(Optional<String> value) {
      return value.map(v -> {
        var uri = URI.create(v.contains("://") ? v : "http://" + v);
        int port = uri.getPort() != -1 ? uri.getPort() : 80;
        return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(uri.getHost(), port));
      });
    }
  }
}
