package gielinor.mod;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import se.krka.kahlua.integration.annotations.LuaMethod;
import zombie.ZomboidFileSystem;
import zombie.gameStates.ChooseGameInfo;

/**
 * Builds Project Gielinor's content pack from inside the game. ZombieBuddy
 * loads this jar with the mod and hands Lua the functions below, which the
 * main menu's build window (`Gielinor_ContentBuild.lua`) uses.
 *
 * The content builder ships beside this jar as a jar of its own and runs as
 * a separate Java process with the game's own Java, so its memory, and
 * anything that goes wrong in it, stay out of the game. It builds into a
 * folder of its own whose files replace the pack's only once it has
 * finished, so a failed build leaves the last pack as it was. Each pack records which
 * builder made it; a pack from any other builder (an older version of the
 * mod) is out of date.
 */
public final class ContentBuild {
    /** The content pack's mod id. */
    static final String PACK_ID = "ProjectGielinorContent";
    /** This mod's id. */
    private static final String MOD_ID = "ProjectGielinor";
    /** The builder, in this mod's version folder. */
    private static final String BUILDER = "media/builder/ProjectGielinorBuilder.jar";
    /** The file in a built pack naming the builder that made it, by the SHA-256 of its jar. */
    private static final String STAMP = "built-by.txt";
    /** How much memory the builder may use (about twice what a build needs at most). */
    private static final String HEAP = "-Xmx3g";
    /** The builder marks the lines that name its steps with this (the rest are details for the log). */
    private static final String STEP = "# ";

    private static final Object LOCK = new Object();
    /** Whether a build is running or being put in place. */
    private static boolean busy;
    /** The builder while it runs, or null. */
    private static Process process;
    /** The step the build is on, or why it failed. */
    private static volatile String progress = "";
    private static volatile boolean failed;
    /** Whether the mods were reloaded for the pack since it was last built (see {@link #mayReload()}). */
    private static boolean reloaded;
    private static String builderHash;

    static {
        // A build still running when the game closes stops with it.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process running = process;
            if (running != null) {
                running.destroyForcibly();
            }
        }));
    }

    /**
     * The content pack's state: "missing", "outdated" (made by another
     * version of the builder), "ready", "building", or "failed" (the last
     * build didn't finish; {@link #progress()} says why).
     */
    @LuaMethod(name = "gielinorContentState", global = true)
    public static String state() {
        synchronized (LOCK) {
            if (busy) {
                return "building";
            }
        }
        if (failed) {
            return "failed";
        }
        Path pack = pack();
        if (!Files.isRegularFile(pack.resolve("42/mod.info"))) {
            return "missing";
        }
        try {
            String stamp = Files.readString(pack.resolve(STAMP), StandardCharsets.UTF_8).trim();
            return stamp.equals(builderHash()) ? "ready" : "outdated";
        } catch (IOException e) {
            return "outdated";
        }
    }

    /** The step the build is on, or why the last one failed. */
    @LuaMethod(name = "gielinorBuildProgress", global = true)
    public static String progress() {
        return progress;
    }

    /** Where the last build's full output is. */
    @LuaMethod(name = "gielinorBuildLog", global = true)
    public static String log() {
        return work().resolve("log.txt").toString();
    }

    /**
     * True only the first time it's asked after a build (or in a session):
     * the build window switches the pack on and reloads the mods at most that
     * often, so a pack the game won't load can't set it reloading forever.
     */
    @LuaMethod(name = "gielinorMayReload", global = true)
    public static boolean mayReload() {
        synchronized (LOCK) {
            boolean first = !reloaded;
            reloaded = true;
            return first;
        }
    }

    /** Starts building the pack in the background. False if a build is already running. */
    @LuaMethod(name = "gielinorBuildContent", global = true)
    public static boolean build() {
        if (!begin()) {
            return false;
        }
        Thread worker = new Thread(() -> run(line -> { }), "Project Gielinor content build");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    /** Builds the pack and waits for it, passing each line of the builder's output on. True if it was built. */
    static boolean buildAndWait(Consumer<String> output) {
        if (!begin()) {
            return false;
        }
        run(output);
        return !failed;
    }

    private static boolean begin() {
        synchronized (LOCK) {
            if (busy) {
                return false;
            }
            busy = true;
            failed = false;
            progress = "Starting";
            return true;
        }
    }

    /**
     * Builds the pack and puts it in place. A finished build from this same
     * builder that didn't get put in place (say the game was closed first)
     * is put in place without building it again.
     */
    private static void run(Consumer<String> output) {
        Path out = work().resolve(PACK_ID);
        try {
            if (!stampedByThisBuilder(out)) {
                deleteTree(work());
                Files.createDirectories(work());
                Process running = start(out);
                synchronized (LOCK) {
                    process = running;
                }
                follow(running, output);
                Files.writeString(out.resolve(STAMP), builderHash() + "\n", StandardCharsets.UTF_8);
            }
            progress = "Putting the pack in place";
            install(out);
            progress = "Done";
            synchronized (LOCK) {
                reloaded = false;
            }
        } catch (IOException | InterruptedException e) {
            fail("The build stopped (" + e.getMessage() + ")");
        } finally {
            synchronized (LOCK) {
                process = null;
                busy = false;
            }
        }
    }

    private static Process start(Path out) throws IOException {
        Path builder = builderJar();
        if (!Files.isRegularFile(builder)) {
            throw new IOException(builder + " is missing; reinstall Project Gielinor");
        }
        File java = new File(System.getProperty("java.home"), "bin/" + (File.separatorChar == '\\' ? "java.exe" : "java"));
        List<String> command = List.of(java.getPath(), HEAP, "-jar", builder.toString(),
            "--zomboid", ZomboidFileSystem.instance.base.canonicalFile.getPath(), "--out", out.toString());
        return new ProcessBuilder(command).directory(work().toFile()).redirectErrorStream(true).start();
    }

    /**
     * Follows a running build to its end, keeping its output in the log and
     * its latest step in {@link #progress}. Throws if it didn't finish.
     */
    private static void follow(Process running, Consumer<String> output) throws IOException, InterruptedException {
        String last = "";
        try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(work().resolve("log.txt"), StandardCharsets.UTF_8), true);
                InputStream stream = running.getInputStream();
                BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                log.println(line);
                output.accept(line);
                if (line.startsWith(STEP)) {
                    progress = line.substring(STEP.length());
                } else if (!line.isBlank()) {
                    last = line.trim();
                }
            }
        }
        int code = running.waitFor();
        if (code != 0) {
            throw new IOException(last.isEmpty() ? "exit code " + code : last);
        }
    }

    private static boolean stampedByThisBuilder(Path pack) {
        try {
            return Files.readString(pack.resolve(STAMP), StandardCharsets.UTF_8).trim().equals(builderHash());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Puts a freshly built pack in the mods folder. A new pack moves there
     * whole. An old one is updated file by file instead: the game watches
     * every folder of the mods it has found for changes, and on Windows a
     * folder can't be renamed while a folder inside it is watched, though
     * the files in them can be replaced. The old pack's stamp goes first and
     * the new one last, so a pack left half updated counts as out of date.
     */
    private static void install(Path built) throws IOException {
        Path pack = pack();
        if (!Files.exists(pack)) {
            Files.createDirectories(pack.getParent());
            Files.move(built, pack);
            return;
        }
        Set<String> files = new HashSet<>();
        try (Stream<Path> paths = Files.walk(built)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                files.add(built.relativize(p).toString());
            }
        }
        try {
            Files.deleteIfExists(pack.resolve(STAMP));
            for (String file : files) {
                if (!file.equals(STAMP)) {
                    Path to = pack.resolve(file);
                    Files.createDirectories(to.getParent());
                    Files.move(built.resolve(file), to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            // What the old pack had and the new one hasn't (folders stay).
            try (Stream<Path> paths = Files.walk(pack)) {
                for (Path p : paths.filter(Files::isRegularFile).toList()) {
                    if (!files.contains(pack.relativize(p).toString())) {
                        Files.delete(p);
                    }
                }
            }
            Files.move(built.resolve(STAMP), pack.resolve(STAMP), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Half moved: the next try builds it all again.
            Files.deleteIfExists(built.resolve(STAMP));
            throw e;
        }
        deleteTree(built);
    }

    private static void fail(String why) {
        progress = why;
        failed = true;
    }

    /**
     * After a build on a server, before its mods load: forgets which mod
     * folders the game had found, so it finds the new pack.
     */
    static void forgetModFolders() {
        ChooseGameInfo.Reset();
        ZomboidFileSystem.instance.resetModFolders();
    }

    /** The pack, in the game's mods folder for local mods. */
    private static Path pack() {
        return Path.of(ZomboidFileSystem.instance.getCacheDir(), "mods", PACK_ID);
    }

    /** The build's own folder: the new pack while it's built, and the log. */
    private static Path work() {
        return Path.of(ZomboidFileSystem.instance.getCacheDir(), "ProjectGielinorBuild");
    }

    private static Path builderJar() {
        ChooseGameInfo.Mod mod = ChooseGameInfo.getModDetails(MOD_ID);
        return Path.of(mod.getVersionDir(), BUILDER);
    }

    /** The SHA-256 of the builder's jar, which names the builder in a pack's stamp. */
    private static String builderHash() {
        synchronized (LOCK) {
            if (builderHash == null) {
                try {
                    MessageDigest sha = MessageDigest.getInstance("SHA-256");
                    builderHash = HexFormat.of().formatHex(sha.digest(Files.readAllBytes(builderJar())));
                } catch (IOException | NoSuchAlgorithmException e) {
                    return "unknown";
                }
            }
            return builderHash;
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
