package gielinor.builder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.runelite.cache.definitions.MapDefinition;

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

    /** The part of the world built so far, in the game's map squares (64 by 64 tiles): Lumbridge and around it. */
    static final int FIRST_SQUARE_X = 48;
    static final int LAST_SQUARE_X = 53;
    static final int FIRST_SQUARE_Y = 48;
    static final int LAST_SQUARE_Y = 53;
    /** Each of the game's tiles is this many Zomboid squares across. */
    static final int SCALE = 2;
    /** Where new characters start: the courtyard of Lumbridge castle, in the game's tile coordinates. */
    static final int START_X = 3222;
    static final int START_Y = 3218;

    /**
     * Writes the ground of {@link #FIRST_SQUARE_X} to {@link #LAST_SQUARE_Y}.
     * The game's world runs north as y grows and Zomboid's south, so rows
     * are turned over; each game tile becomes {@link #SCALE} by {@link #SCALE}
     * squares of the matching Zomboid ground.
     */
    static void write(Path media, TileDefinitions tiles, GielinorTerrain terrain) throws IOException {
        Path dir = media.resolve("maps").resolve(FOLDER);
        // Cells from an earlier build mustn't linger.
        deleteTree(dir);
        Files.createDirectories(dir);
        Map<GielinorTerrain.Ground, List<String>> floors = new EnumMap<>(GielinorTerrain.Ground.class);
        for (GielinorTerrain.Ground g : GielinorTerrain.Ground.values()) {
            List<String> found = tiles.floors(g.sheet, g.material);
            if (found.isEmpty()) {
                throw new IOException("No " + g.material + " tiles in Zomboid's tile definitions");
            }
            floors.put(g, found);
        }
        int width = (LAST_SQUARE_X - FIRST_SQUARE_X + 1) * 64 * SCALE;
        int height = (LAST_SQUARE_Y - FIRST_SQUARE_Y + 1) * 64 * SCALE;
        int cellsX = (width + MapCell.SIZE - 1) / MapCell.SIZE;
        int cellsY = (height + MapCell.SIZE - 1) / MapCell.SIZE;
        MapCell[][] cells = new MapCell[cellsX][cellsY];
        for (int cx = 0; cx < cellsX; cx++) {
            for (int cy = 0; cy < cellsY; cy++) {
                cells[cx][cy] = new MapCell(cx, cy);
            }
        }
        Map<GielinorTerrain.Ground, Integer> counts = new EnumMap<>(GielinorTerrain.Ground.class);
        boolean[][] water = new boolean[width][height];
        for (int sx = FIRST_SQUARE_X; sx <= LAST_SQUARE_X; sx++) {
            for (int sy = FIRST_SQUARE_Y; sy <= LAST_SQUARE_Y; sy++) {
                MapDefinition square = terrain.square(sx, sy);
                if (square == null) {
                    continue;
                }
                for (int lx = 0; lx < 64; lx++) {
                    for (int ly = 0; ly < 64; ly++) {
                        GielinorTerrain.Ground g = terrain.ground(square.getTiles()[0][lx][ly]);
                        if (g == null) {
                            continue;
                        }
                        counts.merge(g, 1, Integer::sum);
                        List<String> choices = floors.get(g);
                        int zx0 = ((sx - FIRST_SQUARE_X) * 64 + lx) * SCALE;
                        int zy0 = height - ((sy - FIRST_SQUARE_Y) * 64 + ly + 1) * SCALE;
                        for (int dx = 0; dx < SCALE; dx++) {
                            for (int dy = 0; dy < SCALE; dy++) {
                                int zx = zx0 + dx;
                                int zy = zy0 + dy;
                                MapCell cell = cells[zx / MapCell.SIZE][zy / MapCell.SIZE];
                                // The same square always gets the same of the material's tiles.
                                int pick = Math.floorMod(scramble(zx, zy), choices.size());
                                cell.set(zx % MapCell.SIZE, zy % MapCell.SIZE, choices.get(pick));
                                if (g == GielinorTerrain.Ground.WATER) {
                                    cell.setBits(zx % MapCell.SIZE, zy % MapCell.SIZE, MapCell.BIT_WATER);
                                    water[zx][zy] = true;
                                }
                            }
                        }
                    }
                }
            }
        }
        // Zombies everywhere but on water.
        for (int cx = 0; cx < cellsX; cx++) {
            for (int cy = 0; cy < cellsY; cy++) {
                for (int kx = 0; kx < MapCell.CHUNKS; kx++) {
                    for (int ky = 0; ky < MapCell.CHUNKS; ky++) {
                        int wet = 0;
                        for (int x = 0; x < MapCell.CHUNK; x++) {
                            for (int y = 0; y < MapCell.CHUNK; y++) {
                                int zx = cx * MapCell.SIZE + kx * MapCell.CHUNK + x;
                                int zy = cy * MapCell.SIZE + ky * MapCell.CHUNK + y;
                                if (zx < width && zy < height && water[zx][zy]) {
                                    wet++;
                                }
                            }
                        }
                        cells[cx][cy].setZombies(kx, ky, wet > MapCell.CHUNK * MapCell.CHUNK / 2 ? 0 : 16);
                    }
                }
                cells[cx][cy].write(dir);
            }
        }
        int startX = (START_X - FIRST_SQUARE_X * 64) * SCALE;
        int startY = height - (START_Y - FIRST_SQUARE_Y * 64 + 1) * SCALE;
        writeInfo(dir);
        writeSpawnPoints(dir, startX, startY);
        Files.writeString(dir.resolve("objects.lua"), "objects = {\n}\n", StandardCharsets.UTF_8);
        System.out.println("  map: " + cellsX * cellsY + " cells, ground " + counts);
    }

    /** A number that looks random but is always the same for a square (a 64-bit mixing function). */
    private static int scramble(int x, int y) {
        long h = (long) x * 0x9E3779B97F4A7C15L + y;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (int) h;
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
