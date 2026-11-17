package io.github.tinnet.wowfam.backend.github;

import io.github.tinnet.wowfam.backend.BackendException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The handful of GitHub REST calls the backend needs. */
public final class GitHubApi {

  public static final URI DEFAULT_BASE = URI.create("https://api.github.com/");
  private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String OCTET_STREAM = "application/octet-stream";
  private static final int MAX_REDIRECTS = 5;

  /** Stop paging after this many pages of 100; monorepos with many tags stay well below it. */
  private static final int MAX_PAGES = 20;

  private final HttpClient http;
  private final URI base;
  private final Optional<String> token;

  public GitHubApi(HttpClient http, URI base, Optional<String> token) {
    this.http = http;
    this.base = base.toString().endsWith("/") ? base : URI.create(base + "/");
    this.token = token.filter(t -> !t.isBlank());
  }

  /**
   * A file attached to a release.
   *
   * @param downloadUrl the asset's API URL, which serves the file with {@code Accept:
   *     application/octet-stream} and takes the token, so private repositories work; the {@code
   *     browser_download_url} on github.com would not
   */
  public record GitHubAsset(String name, URI downloadUrl, long size, Optional<String> digest) {}

  public record GitHubRelease(
      String tag, boolean draft, boolean prerelease, List<GitHubAsset> assets) {}

  /** All published releases of {@code owner/repo}, in the order GitHub returns them. */
  public List<GitHubRelease> releases(String repo) {
    var result = new ArrayList<GitHubRelease>();
    URI next = base.resolve("repos/" + repo + "/releases?per_page=100");
    for (int page = 0; next != null && page < MAX_PAGES; page++) {
      var response =
          send(
              get(next).header("Accept", "application/vnd.github+json").build(),
              HttpResponse.BodyHandlers.ofString());
      check(response, "list releases of " + repo);
      JsonNode listing;
      try {
        listing = JSON.readTree(response.body());
      } catch (JacksonException e) {
        listing = null;
      }
      if (listing == null || !listing.isArray()) {
        // A proxy or captive portal answering in place of GitHub, typically with HTML.
        throw new BackendException(
            "listing releases of "
                + repo
                + " got something other than GitHub's answer from "
                + next);
      }
      for (var node : listing) {
        result.add(release(node));
      }
      next = response.headers().firstValue("Link").map(GitHubApi::nextLink).orElse(null);
    }
    return result;
  }

  /** The body of a small text asset, such as a release's {@code release.json}. */
  public String text(URI url) {
    var response =
        send(get(url).header("Accept", OCTET_STREAM).build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw new BackendException("download of " + url + " failed: HTTP " + response.statusCode());
    }
    return response.body();
  }

  /** Downloads {@code url} to {@code target}, telling {@code progress} the bytes so far. */
  public void download(URI url, Path target, LongConsumer progress) {
    var response =
        send(
            get(url).header("Accept", OCTET_STREAM).build(),
            HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream body = response.body()) {
      if (response.statusCode() / 100 != 2) {
        throw new BackendException("download of " + url + " failed: HTTP " + response.statusCode());
      }
      Files.createDirectories(target.toAbsolutePath().getParent());
      var tmp = target.resolveSibling(target.getFileName() + ".part");
      try (var file = Files.newOutputStream(tmp)) {
        var buffer = new byte[64 * 1024];
        long done = 0;
        for (int n; (n = body.read(buffer)) != -1; ) {
          file.write(buffer, 0, n);
          done += n;
          progress.accept(done);
        }
      }
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new BackendException("download of " + url + " failed: " + e.getMessage(), e);
    }
  }

  private HttpRequest.Builder get(URI uri) {
    var builder =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMinutes(10))
            .header("User-Agent", "wowfam")
            .header("X-GitHub-Api-Version", "2022-11-28");
    // Only send the token to the API host, never to the CDN a download redirects to.
    if (token.isPresent() && uri.getHost() != null && uri.getHost().equals(base.getHost())) {
      builder.header("Authorization", "Bearer " + token.orElseThrow());
    }
    return builder.GET();
  }

  /**
   * Sends {@code request}, following up to {@value #MAX_REDIRECTS} redirects. Each hop is a new
   * request built by {@link #get}, so the token only goes along while the target is the API host:
   * an asset download redirects to a CDN that must not see it.
   */
  private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    var current = request;
    for (int hop = 0; ; hop++) {
      var response = sendOnce(current, handler);
      var location = response.headers().firstValue("Location");
      if (response.statusCode() / 100 != 3 || location.isEmpty() || hop == MAX_REDIRECTS) {
        return response;
      }
      if (response.body() instanceof AutoCloseable body) {
        try {
          body.close();
        } catch (Exception e) {
          // Only the redirect's body, which nobody reads.
        }
      }
      var next = get(current.uri().resolve(location.orElseThrow()));
      current.headers().firstValue("Accept").ifPresent(a -> next.header("Accept", a));
      current = next.build();
    }
  }

  private <T> HttpResponse<T> sendOnce(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    try {
      return http.send(request, handler);
    } catch (IOException e) {
      throw new BackendException("request to " + request.uri() + " failed: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BackendException("interrupted while requesting " + request.uri(), e);
    }
  }

  private void check(HttpResponse<String> response, String what) {
    int status = response.statusCode();
    if (status / 100 == 2) {
      return;
    }
    var hint =
        switch (status) {
          case 404 -> " (repository not found, or private and no GITHUB_TOKEN set)";
          case 401 -> " (GITHUB_TOKEN rejected)";
          case 403, 429 ->
              token.isEmpty()
                  ? " (rate limited? set GITHUB_TOKEN for a higher limit)"
                  : " (rate limited)";
          default -> "";
        };
    throw new BackendException("could not " + what + ": HTTP " + status + hint);
  }

  static @Nullable URI nextLink(String header) {
    var m = NEXT_LINK.matcher(header);
    return m.find() ? URI.create(m.group(1)) : null;
  }

  private static GitHubRelease release(JsonNode node) {
    var assets = new ArrayList<GitHubAsset>();
    for (var a : node.path("assets")) {
      var digest =
          a.path("digest").isString()
              ? Optional.of(a.path("digest").asString())
              : Optional.<String>empty();
      assets.add(
          new GitHubAsset(
              a.path("name").asString(),
              URI.create(
                  a.path("url").isString()
                      ? a.path("url").asString()
                      : a.path("browser_download_url").asString()),
              a.path("size").asLong(),
              digest));
    }
    return new GitHubRelease(
        node.path("tag_name").asString(),
        node.path("draft").asBoolean(),
        node.path("prerelease").asBoolean(),
        assets);
  }
}
