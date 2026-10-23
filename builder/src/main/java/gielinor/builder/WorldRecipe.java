package gielinor.builder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The world recipe package: the imported world without any game art
 * (WORLD-RECIPE-PLAN.md). It holds the layout (maps, tile definitions,
 * generated Lua, spawns, mod.info declarations) and, for every sprite, the
 * recipe to draw it from the player's own game cache. A player's build draws
 * the art (WorldArtBuilder, WorldArtWriter) and lays it beside the layout,
 * giving the same world the converter validated.
 *
 * <pre>
 * world-recipe/
 *   package.json   schema, sprites, contentDigest, SHA-256 of every file
 *   recipes.bin    gzip: "GWR1", sprites, depth bit set, one recipe per sprite
 *   layout/...     the validated stage's files except its art
 * </pre>
 */
final class WorldRecipe {
    private WorldRecipe() {
    }

    static final String RECIPES = "recipes.bin";
    private static final int MAGIC = 0x47575231; // GWR1

    /** Stage files a player's build writes itself; everything else in the payload is layout. */
    static boolean isArt(String path) {
        return path.startsWith("common/media/depthmaps/") || path.startsWith("common/media/texturepacks/")
            || path.matches("(?i).*\\.(png|jpe?g|gif|webp|bmp|pack)$")
            || path.equals("common/media/tileDepthTextureAssignments.txt") || path.equals("common/media/tileGeometry.txt")
            || path.startsWith("common/media/world-depth-") || path.startsWith("common/media/world-colour-")
            || path.startsWith("common/media/gielinor-depth/");
    }

    record Recipes(List<String> recipes, BitSet depthSprites) {
    }

    static void writeRecipes(Path file, List<String> recipes, BitSet depthSprites) throws IOException {
        try (var out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file), 1 << 16)))) {
            out.writeInt(MAGIC);
            out.writeInt(recipes.size());
            byte[] depth = depthSprites.toByteArray();
            out.writeInt(depth.length);
            out.write(depth);
            for (String recipe : recipes) {
                if (recipe == null) throw new IllegalStateException("A sprite has no recipe; the registry predates recipe capture");
                out.writeUTF(recipe);
            }
        }
    }

    static Recipes readRecipes(Path file) throws IOException {
        try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file), 1 << 16)))) {
            if (in.readInt() != MAGIC) throw new IOException("Not a world recipe file: " + file);
            int count = in.readInt();
            if (count < 1 || count > 2_000_000) throw new IOException("Invalid world sprite count");
            int depthBytes = in.readInt();
            if (depthBytes < 0 || depthBytes > (count + 7) / 8) throw new IOException("Invalid world depth bitset");
            byte[] bits = in.readNBytes(depthBytes);
            if (bits.length != depthBytes) throw new EOFException("Truncated world depth bitset");
            var depth = BitSet.valueOf(bits);
            if (depth.length() > count) throw new IOException("Depth bitset exceeds the world sprite count");
            var recipes = new ArrayList<String>(count);
            for (int i = 0; i < count; i++) recipes.add(in.readUTF());
            if (in.read() != -1) throw new IOException("Unexpected data after world recipes");
            return new Recipes(recipes, depth);
        }
    }

    /** The recipes a world registry recorded, and which sprites have a depth frame. */
    static Recipes fromRegistry(int sprites, java.util.function.IntFunction<String> recipe, Collection<String> depthNames) {
        var recipes = new ArrayList<String>(sprites);
        for (int i = 0; i < sprites; i++) recipes.add(recipe.apply(i));
        var depth = new BitSet();
        for (String name : depthNames) {
            int index = WorldArtBuilder.index(name);
            if (index >= 0) depth.set(index);
        }
        return new Recipes(recipes, depth);
    }

    // ---- exporting (here, after a stage validates) ----

    /**
     * Writes the package for a validated stage: its layout files (the
     * validation receipt's payload minus the art) and its recipes (the
     * stage's world-recipe.bin, written by the assembler beside the art).
     * contentDigest is what a player's build of these recipes must draw; the
     * publish step gets it from its own full build, proven against the stage.
     */
    static void export(Path stage, Path out, String contentDigest) throws Exception {
        if (Files.exists(out)) throw new IOException("Export into a new folder: " + out);
        var payload = com.google.gson.JsonParser.parseString(Files.readString(stage.resolve("validation-receipt.json")))
            .getAsJsonObject().getAsJsonObject("payloadSHA256");
        var files = new TreeMap<String, String>();
        for (var entry : payload.entrySet()) {
            String path = entry.getKey().replace('\\', '/');
            Path source = input(stage, path);
            if (!sha(source).equals(entry.getValue().getAsString())) throw new IOException("Stage file changed since validation: " + path);
            if (isArt(path)) continue;
            Path target = safe(out, "layout/" + path);
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
            files.put("layout/" + path, entry.getValue().getAsString());
        }
        Files.copy(stage.resolve("world-status.json"), out.resolve("layout/world-status.json"));
        files.put("layout/world-status.json", sha(out.resolve("layout/world-status.json")));
        Files.copy(stage.resolve("world-recipe.bin"), out.resolve(RECIPES));
        files.put(RECIPES, sha(out.resolve(RECIPES)));
        var json = new com.google.gson.JsonObject();
        json.addProperty("schema", 1);
        json.addProperty("sprites", readRecipes(out.resolve(RECIPES)).recipes().size());
        json.addProperty("contentDigest", contentDigest);
        var hashes = new com.google.gson.JsonObject();
        files.forEach(hashes::addProperty);
        json.add("files", hashes);
        Files.writeString(out.resolve("package.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n");
    }

    // ---- building (on the player's machine) ----

    interface Progress {
        void step(String what);

        void drawn(int sprites, int total);
    }

    /**
     * Builds the world from a package into a stage-like folder: the layout
     * copied, the art drawn from the player's cache and written beside it.
     * Skips the work when that folder already holds a build of this package.
     * Fails, leaving no build, if the drawing differs from what was validated
     * (a different game cache).
     */
    static Path build(Path pack, File cache, Path zomboid, Path out, int threads, Progress progress) throws Exception {
        if (threads < 1 || threads > 64) throw new IOException("World drawing threads must be between 1 and 64");
        pack = pack.toRealPath();
        out = out.toAbsolutePath().normalize();
        if (out.startsWith(pack) || pack.startsWith(out)) throw new IOException("World output must be separate from its recipe package");
        progress.step("Checking the world recipe");
        var manifest = validatePackage(pack);
        String packageSha = sha(pack.resolve("package.json"));
        String digest = manifest.get("contentDigest").getAsString();
        String gameSha = sha(zomboid.resolve("projectzomboid.jar"));
        String builderSha = builderIdentity();
        Path receipt = out.resolve("built-world.json");
        if (Files.exists(receipt)) {
            var built = com.google.gson.JsonParser.parseString(Files.readString(receipt)).getAsJsonObject();
            if (built.has("gameJarSHA256") && built.has("payloadSHA256") && built.has("builderSHA256")
                    && built.get("builderSHA256").getAsString().equals(builderSha)
                    && built.get("gameJarSHA256").getAsString().equals(gameSha)
                    && built.get("packageSHA256").getAsString().equals(packageSha)
                    && built.get("contentDigest").getAsString().equals(digest)) {
                try {
                    verifyPayload(out, built.getAsJsonObject("payloadSHA256"));
                    progress.step("The world is already built from this version");
                    return out;
                } catch (IOException damaged) {
                    // Keep the prior directory until its replacement succeeds.
                    // A persistent cache must not make every retry fail forever.
                    progress.step("Rebuilding the damaged world cache");
                }
            }
        }
        if (Files.exists(out) && !Files.isRegularFile(receipt)) throw new IOException("Refusing to replace a folder that isn't a world build: " + out);
        if (Files.isSymbolicLink(out)) throw new IOException("World output must not be a symbolic link");
        var recipes = readRecipes(pack.resolve(RECIPES));
        if (recipes.recipes().size() != manifest.get("sprites").getAsInt()) throw new IOException("World recipe sprite count differs from its manifest");

        Files.createDirectories(out.getParent());
        Path building = Files.createTempDirectory(out.getParent(), out.getFileName() + ".building-");
        try {
        WorldArt.holesFromGame(zomboid);
        progress.step("Drawing the world's art from your game cache");
        String drawn;
        try (var writer = new WorldArtWriter(recipes.recipes().size(), recipes.depthSprites(), building.resolve("frames.tmp"))) {
            WorldArtBuilder.draw(recipes.recipes(), cache, threads, writer, progress::drawn);
            drawn = writer.contentDigest();
            if (!drawn.equals(digest))
                throw new IOException("The art drawn from this game cache differs from the validated world (digest " + drawn + ", expected " + digest + ")");
            progress.step("Writing the world's textures");
            writer.write(building.resolve("common/media"), threads, zomboid.resolve("projectzomboid.jar"));
        }
        progress.step("Laying out the map");
        Path layout = pack.resolve("layout");
        try (var paths = Files.walk(layout)) {
            for (Path source : paths.filter(Files::isRegularFile).toList()) {
                Path target = building.resolve(layout.relativize(source).toString());
                Files.createDirectories(target.getParent());
                Path checked = input(pack, "layout/" + layout.relativize(source).toString().replace('\\', '/'));
                Files.copy(checked, target);
            }
        }
        var built = new com.google.gson.JsonObject();
        built.addProperty("packageSHA256", packageSha);
        built.addProperty("contentDigest", drawn);
        built.addProperty("gameJarSHA256", gameSha);
        built.addProperty("builderSHA256", builderSha);
        var payload = new com.google.gson.JsonObject();
        try (var paths = Files.walk(building)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList())
                payload.addProperty(building.relativize(path).toString().replace('\\', '/'), sha(path));
        }
        built.add("payloadSHA256", payload);
        Files.writeString(building.resolve("built-world.json"), built + "\n", StandardCharsets.UTF_8);
        Path previous = out.resolveSibling(out.getFileName() + ".previous-" + UUID.randomUUID());
        boolean hadPrevious = Files.exists(out);
        if (hadPrevious) Files.move(out, previous);
        try { Files.move(building, out); }
        catch (IOException failure) {
            if (hadPrevious) Files.move(previous, out);
            throw failure;
        }
        if (hadPrevious) deleteTree(previous);
        return out;
        } finally { deleteTree(building); }
    }

    static com.google.gson.JsonObject validatePackage(Path pack) throws Exception {
        var manifest = com.google.gson.JsonParser.parseString(Files.readString(input(pack, "package.json"))).getAsJsonObject();
        if (manifest.get("schema").getAsInt() != 1) throw new IOException("Unsupported world recipe schema");
        int sprites = manifest.get("sprites").getAsInt();
        if (sprites < 1 || sprites > 2_000_000 || !manifest.get("contentDigest").getAsString().matches("[a-f0-9]{64}"))
            throw new IOException("Invalid world recipe manifest");
        var files = manifest.getAsJsonObject("files");
        if (files == null || !files.has(RECIPES) || files.size() > 100_000) throw new IOException("World recipe manifest is incomplete");
        for (var file : files.entrySet()) {
            if (!file.getKey().equals(RECIPES) && !file.getKey().startsWith("layout/")) throw new IOException("Unexpected world package file");
            if (file.getKey().startsWith("layout/") && isArt(file.getKey().substring(7))) throw new IOException("Game art must be built locally, not shipped in the layout");
            if (!sha(input(pack, file.getKey())).equals(file.getValue().getAsString())) throw new IOException("World recipe file damaged: " + file.getKey());
        }
        Path layout = input(pack, "layout");
        try (var paths = Files.walk(layout)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String key = "layout/" + layout.relativize(path).toString().replace('\\', '/');
                if (!files.has(key)) throw new IOException("Unlisted world layout file: " + key);
                input(pack, key);
            }
        }
        try (var paths = Files.walk(pack)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String key = pack.relativize(path).toString().replace('\\', '/');
                if (!key.equals("package.json") && !key.equals("publish-proof.json") && !files.has(key))
                    throw new IOException("Unlisted world package file: " + key);
                input(pack, key);
            }
        }
        return manifest;
    }

    static void verifyPayload(Path root, com.google.gson.JsonObject payload) throws Exception {
        for (var file : payload.entrySet())
            if (!sha(input(root, file.getKey())).equals(file.getValue().getAsString())) throw new IOException("Built world file damaged: " + file.getKey());
    }

    static Path safe(Path root, String relative) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path path = base.resolve(relative).normalize();
        if (relative.isEmpty() || relative.contains("\\") || Path.of(relative).isAbsolute() || !path.startsWith(base) || path.equals(base))
            throw new IOException("Unsafe world package path: " + relative);
        return path;
    }

    static Path input(Path root, String relative) throws IOException {
        Path path = safe(root, relative).toRealPath();
        if (!path.startsWith(root.toRealPath())) throw new IOException("World package path leaves its root");
        return path;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    static String sha(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = new byte[1 << 20];
            for (int n; (n = in.read(bytes)) != -1;) digest.update(bytes, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String builderIdentity() throws Exception {
        Path program = Path.of(WorldRecipe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(program)) return sha(program);
        // Unit tests run unpacked classes; published builds always use the jar.
        try (var in = WorldRecipe.class.getResourceAsStream("WorldRecipe.class")) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        }
    }
}
