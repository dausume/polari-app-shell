package org.polari.shell.desktop.hardware;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SSLContext;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Step 1: get a bundle from a directory, a tar.gz, or Polari's export
 * store. The only outbound calls here are GET /api/firmware/exports
 * and the chosen download_url; nothing about this machine is sent.
 */
public final class BundleSource {

    private BundleSource() {}

    public static Bundle open(Path chosen) throws Exception {
        if (Files.isDirectory(chosen)) {
            return Bundle.at(chosen);
        }
        Path work = Files.createTempDirectory("polari-hw-bundle-");
        // fixed argv; GNU tar already drops absolute and .. members
        HostProcessResult r = tarExtract(chosen, work);
        if (r.code != 0) {
            throw new IllegalStateException("could not unpack "
                    + chosen.getFileName() + ": " + r.out.strip());
        }
        return Bundle.at(work);
    }

    private record HostProcessResult(int code, String out) {}

    private static HostProcessResult tarExtract(Path tgz, Path into)
            throws Exception {
        var r = org.polari.shell.desktop.HostProcess.run(
                List.of("tar", "-xzf", tgz.toString(), "-C",
                        into.toString(), "--no-same-owner"), 120);
        return new HostProcessResult(r.exitCode, r.output);
    }

    /** The newest form=demo-bundle row for the solution, or null. */
    public static JsonObject latest(String exportsJson,
                                    String solution) {
        JsonElement root = JsonParser.parseString(exportsJson);
        JsonArray rows = root.isJsonObject()
                && root.getAsJsonObject().has("rows")
                ? root.getAsJsonObject().getAsJsonArray("rows")
                : new JsonArray();
        JsonObject best = null;
        for (JsonElement e : rows) {
            JsonObject o = e.getAsJsonObject();
            if (!"demo-bundle".equals(str(o, "form"))
                    || !solution.equals(str(o, "solution"))
                    || str(o, "download_url").isEmpty()) {
                continue;
            }
            if (best == null || str(o, "created_at")
                    .compareTo(str(best, "created_at")) > 0) {
                best = o;
            }
        }
        return best;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive()
                ? o.get(k).getAsString() : "";
    }

    public static Bundle fetch(String apiBase, String solution,
                               SSLContext trust) throws Exception {
        HttpClient http = client(trust);
        String base = apiBase.replaceAll("/+$", "");
        HttpResponse<String> list = http.send(
                HttpRequest.newBuilder(URI.create(
                        base + "/api/firmware/exports"))
                        .timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (list.statusCode() != 200) {
            throw new IllegalStateException("Polari answered "
                    + list.statusCode() + " for the export list.");
        }
        JsonObject row = latest(list.body(), solution);
        if (row == null) {
            throw new IllegalStateException("Polari has no demo "
                    + "bundle for " + solution + " yet. Build one, or "
                    + "open a bundle from disk.");
        }
        Path tgz = Files.createTempFile("polari-hw-bundle-", ".tar.gz");
        HttpResponse<Path> dl = http.send(
                HttpRequest.newBuilder(URI.create(
                        base + str(row, "download_url")))
                        .timeout(Duration.ofSeconds(120)).GET().build(),
                HttpResponse.BodyHandlers.ofFile(tgz));
        if (dl.statusCode() != 200) {
            throw new IllegalStateException("Polari answered "
                    + dl.statusCode() + " for the bundle download.");
        }
        String want = str(row, "tar_sha256");
        if (!want.isEmpty() && !want.equalsIgnoreCase(
                BundleGate.sha256(tgz))) {
            throw new IllegalStateException("Refused: the download's "
                    + "sha256 differs from the export row's "
                    + "tar_sha256 (" + want + ").");
        }
        return open(tgz);
    }

    static HttpClient client(SSLContext trust) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (trust != null) {
            b.sslContext(trust);
        }
        return b.build();
    }
}
