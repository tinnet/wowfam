package io.github.tinnet.wowfam.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Proxy;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpTest {

  private final Http.EnvProxySelector selector =
      (Http.EnvProxySelector)
          Http.EnvProxySelector.from(
                  Map.of(
                      "https_proxy", "http://proxy.local:3128",
                      "NO_PROXY", "localhost,127.0.0.1,.internal,example.org"))
              .orElseThrow();

  @Test
  void proxiesHttpsOnly() {
    assertThat(selector.select(URI.create("https://api.github.com/x")).getFirst().type())
        .isEqualTo(Proxy.Type.HTTP);
    assertThat(selector.select(URI.create("http://api.github.com/x")))
        .containsExactly(Proxy.NO_PROXY);
  }

  @Test
  void honoursNoProxy() {
    assertThat(selector.select(URI.create("https://127.0.0.1:8080/")))
        .containsExactly(Proxy.NO_PROXY);
    assertThat(selector.bypass("a.internal")).isTrue();
    assertThat(selector.bypass("internal")).isTrue();
    assertThat(selector.bypass("cdn.example.org")).isTrue();
    assertThat(selector.bypass("notexample.org")).isFalse();
  }

  @Test
  void noVariablesMeansNoSelector() {
    assertThat(Http.EnvProxySelector.from(Map.of())).isEmpty();
  }
}
