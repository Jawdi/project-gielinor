package gielinor.builder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The one version of the game the content pack is built from, so every
 * player (and every multiplayer server) gets the same items and the same
 * world whatever version of the game they have installed. It comes from the
 * OpenRS2 archive, which keeps every version: the cache, and the keys that
 * unlock its map. Both are fetched once and kept for later builds.
 */
final class Pinned {
    /** The OpenRS2 cache the pack is built from (game build 241). */
    static final int CACHE = 2735;
    /**
     * The OpenRS2 cache whose map keys unlock {@link #CACHE}'s map: the newest
     * with keys (build 236). Every keyed map square of build 241 unlocks with
     * them.
     */
    static final int KEYS = 2499;

    private static final String ARCHIVE = "https://archive.openrs2.org/caches/runescape/";

    private Pinned() {
    }

    /** Where fetched files are kept between builds. */
    static Path store() {
        String local = System.getenv("LOCALAPPDATA");
        Path base = local != null ? Path.of(local) : Path.of(System.getProperty("user.home"), ".local", "share");
        return base.resolve("ProjectGielinor").resolve("openrs2");
    }

    /** The pinned cache's folder, fetched and unpacked if it isn't there yet. */
    static File cache() throws IOException, InterruptedException {
        Path dir = store().resolve("cache-" + CACHE);
        if (!Locations.isCache(dir.toFile())) {
            Path zip = fetch(ARCHIVE + CACHE + "/disk.zip", store().resolve("cache-" + CACHE + ".zip"), "the game cache");
            Main.step("Extracting the game cache");
            unpack(zip, dir);
            Files.delete(zip);
            if (!Locations.isCache(dir.toFile())) {
                throw new IOException("The downloaded cache in " + dir + " is incomplete. Delete that folder and build again.");
            }
        }
        return dir.toFile();
    }

    /** The map keys file, fetched if it isn't there yet. */
    static Path keys() throws IOException, InterruptedException {
        Path file = store().resolve("keys-" + KEYS + ".json");
        if (!Files.isRegularFile(file)) {
            fetch(ARCHIVE + KEYS + "/keys.json", file, "the map keys");
        }
        return file;
    }

    /**
     * Downloads `url` to `target` (by way of a temporary file, so a broken
     * download leaves nothing behind), reporting each few megabytes.
     */
    private static Path fetch(String url, Path target, String what) throws IOException, InterruptedException {
        Main.step("Downloading " + what + " from the OpenRS2 archive");
        Files.createDirectories(target.getParent());
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(URI.create(url)).build(),
            HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Couldn't download " + url + " (HTTP " + response.statusCode() + ")");
            }
            long size = response.headers().firstValueAsLong("content-length").orElse(-1);
            try (java.io.OutputStream out = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[1 << 16];
                long done = 0;
                long reported = 0;
                for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                    out.write(buffer, 0, n);
                    done += n;
                    if (done - reported >= 5 << 20) {
                        reported = done;
                        Main.step("Downloading " + what + ": " + (done >> 20) + (size > 0 ? " of " + (size >> 20) : "") + " MB");
                    }
                }
            }
        } catch (IOException e) {
            Files.deleteIfExists(partial);
            throw e;
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** Unpacks the archive's `cache/` folder into `dir`. */
    private static void unpack(Path zip, Path dir) throws IOException {
        Files.createDirectories(dir);
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                String name = e.getName();
                if (e.isDirectory() || !name.startsWith("cache/")) {
                    continue;
                }
                Path out = dir.resolve(name.substring("cache/".length())).normalize();
                if (!out.startsWith(dir)) {
                    throw new IOException("Unexpected path in the cache archive: " + name);
                }
                copy(in, out);
            }
        }
    }

    private static void copy(InputStream in, Path out) throws IOException {
        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
    }
}
