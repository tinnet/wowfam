package io.github.tinnet.wowfam;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.tinnet.wowfam.backend.Backends;
import io.github.tinnet.wowfam.backend.Http;
import io.github.tinnet.wowfam.backend.github.GitHubApi;
import io.github.tinnet.wowfam.backend.github.GitHubBackend;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import tools.jackson.databind.json.JsonMapper;

/**
 * An in-process stand-in for the GitHub releases API: {@code GET /repos/o/r/releases} (paged,
 * newest first, with {@code Link} headers) and asset downloads.
 */
public final class FakeGitHub implements AutoCloseable {

  @SuppressWarnings("ArrayRecordComponent") // test fixture; never compared
  public record FakeAsset(String name, byte[] content) {}

  public record FakeRelease(
      String repo, String tag, boolean prerelease, boolean draft, List<FakeAsset> assets) {}

  private final HttpServer server;
  private final List<FakeRelease> releases = new CopyOnWriteArrayList<>();
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private int pageSize = 100;

  public FakeGitHub() {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/", this::handle);
    server.start();
  }

  public URI base() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
  }

  public FakeGitHub pageSize(int pageSize) {
    this.pageSize = pageSize;
    return this;
  }

  /** Adds a release; later additions are newer. */
  public FakeGitHub release(String repo, String tag, FakeAsset... assets) {
    return release(new FakeRelease(repo, tag, false, false, List.of(assets)));
  }

  public FakeGitHub release(FakeRelease release) {
    releases.addFirst(release);
    return this;
  }

  public List<String> requests() {
    return List.copyOf(requests);
  }

  public Backends backends() {
    return new Backends(
        List.of(new GitHubBackend(new GitHubApi(Http.client(Map.of()), base(), Optional.empty()))));
  }

  /** A zip whose entries are {@code path -> content}; paths ending in {@code /} are folders. */
  public static FakeAsset zip(String name, Map<String, String> entries) {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      for (var e : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(e.getKey()));
        zip.write(e.getValue().getBytes(UTF_8));
        zip.closeEntry();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new FakeAsset(name, bytes.toByteArray());
  }

  /**
   * A zip with one {@code <Folder>/<Folder>.toc} per folder, carrying {@code ## Version} and
   * optional deps.
   */
  public static FakeAsset addonZip(String name, String version, Map<String, String> folderToDeps) {
    var entries = new LinkedHashMap<String, String>();
    folderToDeps.forEach((folder, deps) -> {
      var toc =
          "## Interface: 11508\n## Title: "
              + folder
              + "\n## Version: "
              + version
              + "\n"
              + (deps.isEmpty() ? "" : "## Dependencies: " + deps + "\n")
              + "Core.lua\n";
      entries.put(folder + "/" + folder + ".toc", toc);
      entries.put(folder + "/Core.lua", "-- " + folder + " " + version + "\n");
    });
    return zip(name, entries);
  }

  /**
   * A {@code release.json} as the BigWigs packager writes it: {@code filename -> flavors}; a
   * filename containing {@code nolib} is marked as such.
   */
  public static FakeAsset releaseJson(Map<String, List<String>> filesToFlavors) {
    var releases = new ArrayList<Map<String, Object>>();
    filesToFlavors.forEach((filename, flavors) ->
        releases.add(
            Map.of(
                "name",
                "Addon",
                "version",
                "v1",
                "filename",
                filename,
                "nolib",
                filename.contains("nolib"),
                "metadata",
                flavors.stream()
                    .map(f -> Map.<String, Object>of("flavor", f, "interface", 11508))
                    .toList())));
    var json = JsonMapper.builder().build().writeValueAsString(Map.of("releases", releases));
    return new FakeAsset("release.json", json.getBytes(UTF_8));
  }

  private void handle(HttpExchange exchange) throws IOException {
    var path = exchange.getRequestURI().getPath();
    requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
    try (exchange) {
      if (path.startsWith("/download/")) {
        download(exchange, path.substring("/download/".length()));
      } else if (path.startsWith("/repos/") && path.endsWith("/releases")) {
        list(exchange, path.substring("/repos/".length(), path.length() - "/releases".length()));
      } else {
        send(exchange, 404, "{\"message\":\"Not Found\"}");
      }
    }
  }

  private void list(HttpExchange exchange, String repo) throws IOException {
    var mine = releases.stream().filter(r -> r.repo().equals(repo)).toList();
    if (mine.isEmpty()) {
      send(exchange, 404, "{\"message\":\"Not Found\"}");
      return;
    }
    int page = queryInt(exchange.getRequestURI(), "page", 1);
    int from = (page - 1) * pageSize;
    var slice = mine.subList(Math.min(from, mine.size()), Math.min(from + pageSize, mine.size()));
    var json = new ArrayList<Map<String, Object>>();
    for (var r : slice) {
      var assets = new ArrayList<Map<String, Object>>();
      for (var a : r.assets()) {
        assets.add(
            Map.of(
                "name",
                a.name(),
                "size",
                a.content().length,
                "digest",
                "sha256:" + sha256(a.content()),
                "browser_download_url",
                base() + "download/" + r.tag() + "/" + a.name()));
      }
      json.add(
          Map.of(
              "tag_name",
              r.tag(),
              "draft",
              r.draft(),
              "prerelease",
              r.prerelease(),
              "assets",
              assets));
    }
    if (from + pageSize < mine.size()) {
      exchange
          .getResponseHeaders()
          .add(
              "Link",
              "<"
                  + base()
                  + "repos/"
                  + repo
                  + "/releases?per_page="
                  + pageSize
                  + "&page="
                  + (page + 1)
                  + ">; rel=\"next\"");
    }
    send(exchange, 200, JsonMapper.builder().build().writeValueAsString(json));
  }

  private void download(HttpExchange exchange, String tagAndName) throws IOException {
    int slash = tagAndName.lastIndexOf('/');
    var tag = tagAndName.substring(0, slash);
    var name = tagAndName.substring(slash + 1);
    var asset =
        releases.stream()
            .filter(r -> r.tag().equals(tag))
            .flatMap(r -> r.assets().stream())
            .filter(a -> a.name().equals(name))
            .findFirst();
    if (asset.isEmpty()) {
      send(exchange, 404, "not found");
      return;
    }
    exchange.sendResponseHeaders(200, asset.orElseThrow().content().length);
    exchange.getResponseBody().write(asset.orElseThrow().content());
  }

  private static int queryInt(URI uri, String key, int dflt) {
    if (uri.getQuery() == null) {
      return dflt;
    }
    for (var kv : uri.getQuery().split("&")) {
      var parts = kv.split("=", 2);
      if (parts[0].equals(key) && parts.length == 2) {
        return Integer.parseInt(parts[1]);
      }
    }
    return dflt;
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    var bytes = body.getBytes(UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
