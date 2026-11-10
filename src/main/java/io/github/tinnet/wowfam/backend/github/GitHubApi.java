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
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The handful of GitHub REST calls the backend needs. */
public final class GitHubApi {

  public static final URI DEFAULT_BASE = URI.create("https://api.github.com/");
  private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
  private static final JsonMapper JSON = JsonMapper.builder().build();

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
      for (var node : JSON.readTree(response.body())) {
        result.add(release(node));
      }
      next = response.headers().firstValue("Link").map(GitHubApi::nextLink).orElse(null);
    }
    return result;
  }

  public void download(URI url, Path target) {
    var response =
        send(
            get(url).header("Accept", "application/octet-stream").build(),
            HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream body = response.body()) {
      if (response.statusCode() / 100 != 2) {
        throw new BackendException("download of " + url + " failed: HTTP " + response.statusCode());
      }
      Files.createDirectories(target.toAbsolutePath().getParent());
      var tmp = target.resolveSibling(target.getFileName() + ".part");
      Files.copy(body, tmp, StandardCopyOption.REPLACE_EXISTING);
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

  private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
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
              URI.create(a.path("browser_download_url").asString()),
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
