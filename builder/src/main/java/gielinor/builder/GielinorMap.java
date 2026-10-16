package gielinor.builder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/**
 * The Gielinor world as a standalone Zomboid map (`media/maps/Gielinor`),
 * laid out like the game's own standalone maps: its cells, `map.info` with no
 * `lots` line (so it isn't laid over Kentucky), `spawnpoints.lua` and
 * `objects.lua`. A map that isn't laid over another is a world of its own:
 * Zomboid's World Select screen offers it beside Kentucky when a new game
 * starts, and servers set `Map=Gielinor`.
 */
final class GielinorMap {
    static final String FOLDER = "Gielinor";

    private GielinorMap() {
    }

    /**
     * For now a single test cell of grass, to check the map loads before
     * the game's terrain goes in. Players start in its middle.
     */
    static void write(Path media, TileDefinitions tiles) throws IOException {
        Path dir = media.resolve("maps").resolve(FOLDER);
        // Cells from an earlier build mustn't linger.
        deleteTree(dir);
        Files.createDirectories(dir);
        List<String> grass = tiles.floors("blends_natural_01", "Grass_Dark");
        if (grass.isEmpty()) {
            throw new IOException("No grass tiles in Zomboid's tile definitions");
        }
        MapCell cell = new MapCell(0, 0);
        Random random = new Random(1);
        for (int y = 0; y < MapCell.SIZE; y++) {
            for (int x = 0; x < MapCell.SIZE; x++) {
                cell.set(x, y, grass.get(random.nextInt(grass.size())));
            }
        }
        for (int cy = 0; cy < MapCell.CHUNKS; cy++) {
            for (int cx = 0; cx < MapCell.CHUNKS; cx++) {
                cell.setZombies(cx, cy, 16);
            }
        }
        cell.write(dir);
        int middle = MapCell.SIZE / 2;
        writeInfo(dir);
        writeSpawnPoints(dir, middle, middle);
        Files.writeString(dir.resolve("objects.lua"), "objects = {\n}\n", StandardCharsets.UTF_8);
        System.out.println("  map: 1 test cell");
    }

    private static void deleteTree(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
    }

    private static void writeInfo(Path dir) throws IOException {
        Files.writeString(dir.resolve("map.info"), String.join("\n",
            "title=Gielinor",
            "fixed2x=true",
            "description=The world of Gielinor, built on this machine by the Project Gielinor content builder.",
            ""), StandardCharsets.UTF_8);
    }

    /** Every profession starts at the one point for now. */
    private static void writeSpawnPoints(Path dir, int x, int y) throws IOException {
        Files.writeString(dir.resolve("spawnpoints.lua"), "function SpawnPoints()\n    local start = {\n"
            + "        { posX = " + x + ", posY = " + y + ", posZ = 0 },\n    }\n"
            + "    return {\n        unemployed = start,\n    }\nend\n", StandardCharsets.UTF_8);
    }
}
