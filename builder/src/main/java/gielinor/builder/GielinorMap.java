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
     * Each chunk's zombie density. Kentucky's own map gives its populated
     * chunks about 2 (and most none, averaging 0.07); every chunk of land
     * here gets 1 for now.
     */
    static final int ZOMBIE_DENSITY = 1;
    /** No zombies within this many squares of where characters start, so they start safe in Lumbridge castle. */
    static final int SAFE_RADIUS = 80;

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
        GielinorTerrain.Ground[][] ground = new GielinorTerrain.Ground[width][height];
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
                                ground[zx][zy] = g;
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
        // Zombies everywhere but on water and around where characters start.
        int startX = (START_X - FIRST_SQUARE_X * 64) * SCALE;
        int startY = height - (START_Y - FIRST_SQUARE_Y * 64 + 1) * SCALE;
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
                        int middleX = cx * MapCell.SIZE + kx * MapCell.CHUNK + MapCell.CHUNK / 2;
                        int middleY = cy * MapCell.SIZE + ky * MapCell.CHUNK + MapCell.CHUNK / 2;
                        boolean nearStart = Math.hypot(middleX - startX, middleY - startY) < SAFE_RADIUS;
                        boolean onWater = wet > MapCell.CHUNK * MapCell.CHUNK / 2;
                        cells[cx][cy].setZombies(kx, ky, nearStart || onWater ? 0 : ZOMBIE_DENSITY);
                    }
                }
                cells[cx][cy].write(dir);
            }
        }
        writeInfo(dir);
        writeWorldMap(dir, ground, cellsX, cellsY);
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

    /**
     * What the in-game map draws each ground as, keyed by the property each
     * is drawn by: water as Zomboid's rivers, the rest by the mod's own map
     * layers (`gielinor` = stone, sand or dirt). Grass is left as the map's
     * plain background. Zomboid's road layers won't do: it triangulates
     * roads at six levels of detail, and a cell of the game's small patches
     * of ground then overflows the 16-bit count of where each feature's
     * triangles start, which crashes the map.
     */
    private static String[] mapFeature(GielinorTerrain.Ground g) {
        return switch (g) {
            case WATER -> new String[]{"water", "river"};
            case STONE -> new String[]{"gielinor", "stone"};
            case SAND -> new String[]{"gielinor", "sand"};
            case DIRT -> new String[]{"gielinor", "dirt"};
            default -> null;
        };
    }

    /**
     * Writes the map Zomboid shows (the M key, and the maps found as loot):
     * per cell, its ground as rectangles of one feature each, made by running
     * along each row and stacking equal runs of the rows below onto them.
     * Points are in squares from the cell's corner.
     *
     * The data goes in `worldmap.xml.bin`, the binary form the game's own
     * maps ship: Zomboid's reader of the XML form miscounts each polygon's
     * points (as twice as many) and fails on every one. The map screen only
     * looks for data where a `worldmap.xml` exists, so an empty one stands
     * beside it.
     */
    private static void writeWorldMap(Path dir, GielinorTerrain.Ground[][] ground, int cellsX, int cellsY) throws IOException {
        int width = ground.length;
        int height = ground[0].length;
        List<String> strings = new java.util.ArrayList<>(List.of("Polygon"));
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        int features = 0;
        for (int cy = 0; cy < cellsY; cy++) {
            for (int cx = 0; cx < cellsX; cx++) {
                int x0 = cx * MapCell.SIZE;
                int y0 = cy * MapCell.SIZE;
                int x1 = Math.min(x0 + MapCell.SIZE, width);
                int y1 = Math.min(y0 + MapCell.SIZE, height);
                boolean[][] done = new boolean[MapCell.SIZE][MapCell.SIZE];
                List<int[]> rects = new java.util.ArrayList<>();
                List<String[]> kinds = new java.util.ArrayList<>();
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        GielinorTerrain.Ground g = ground[x][y];
                        String[] feature = g == null ? null : mapFeature(g);
                        if (feature == null || done[x - x0][y - y0]) {
                            continue;
                        }
                        int right = x;
                        while (right + 1 < x1 && ground[right + 1][y] == g && !done[right + 1 - x0][y - y0]) {
                            right++;
                        }
                        int bottom = y;
                        while (bottom + 1 < y1 && sameRun(ground, done, x0, y0, x, right, bottom + 1, g)) {
                            bottom++;
                        }
                        for (int ry = y; ry <= bottom; ry++) {
                            for (int rx = x; rx <= right; rx++) {
                                done[rx - x0][ry - y0] = true;
                            }
                        }
                        rects.add(new int[]{x - x0, y - y0, right + 1 - x0, bottom + 1 - y0});
                        kinds.add(feature);
                    }
                }
                if (rects.isEmpty()) {
                    writeInt(bytes, -1);
                    continue;
                }
                writeInt(bytes, cx);
                writeInt(bytes, cy);
                writeInt(bytes, rects.size());
                for (int i = 0; i < rects.size(); i++) {
                    int[] r = rects.get(i);
                    // Geometry: its type, one ring of four corners.
                    writeShort(bytes, index(strings, "Polygon"));
                    bytes.write(1);
                    writeShort(bytes, 4);
                    int[][] corners = {{r[0], r[1]}, {r[2], r[1]}, {r[2], r[3]}, {r[0], r[3]}};
                    for (int[] c : corners) {
                        writeShort(bytes, c[0]);
                        writeShort(bytes, c[1]);
                    }
                    // Properties: one name and value.
                    bytes.write(1);
                    writeShort(bytes, index(strings, kinds.get(i)[0]));
                    writeShort(bytes, index(strings, kinds.get(i)[1]));
                }
                features += rects.size();
            }
        }
        java.io.ByteArrayOutputStream file = new java.io.ByteArrayOutputStream();
        file.writeBytes("IGMB".getBytes(StandardCharsets.US_ASCII));
        writeInt(file, 2);
        writeInt(file, MapCell.SIZE);
        writeInt(file, cellsX);
        writeInt(file, cellsY);
        writeInt(file, strings.size());
        for (String s : strings) {
            byte[] utf = s.getBytes(StandardCharsets.UTF_8);
            writeShort(file, utf.length);
            file.writeBytes(utf);
        }
        file.writeBytes(bytes.toByteArray());
        Files.write(dir.resolve("worldmap.xml.bin"), file.toByteArray());
        Files.writeString(dir.resolve("worldmap.xml"),
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- The map's data is in worldmap.xml.bin. -->\n<world version=\"1.0\">\n</world>\n",
            StandardCharsets.UTF_8);
        System.out.println("  world map: " + features + " features");
    }

    /** Whether row `y` from `x` to `right` is all ground `g` and not yet drawn. */
    private static boolean sameRun(GielinorTerrain.Ground[][] ground, boolean[][] done, int x0, int y0, int x, int right, int y,
            GielinorTerrain.Ground g) {
        for (int rx = x; rx <= right; rx++) {
            if (ground[rx][y] != g || done[rx - x0][y - y0]) {
                return false;
            }
        }
        return true;
    }

    /** A string's place in the binary map's string table, adding it if new. */
    private static int index(List<String> strings, String s) {
        int i = strings.indexOf(s);
        if (i < 0) {
            strings.add(s);
            i = strings.size() - 1;
        }
        return i;
    }

    /** Little-endian, as the binary map is read. */
    private static void writeInt(java.io.ByteArrayOutputStream out, int v) {
        for (int i = 0; i < 4; i++) {
            out.write(v >>> (8 * i));
        }
    }

    private static void writeShort(java.io.ByteArrayOutputStream out, int v) {
        out.write(v);
        out.write(v >>> 8);
    }

    /** Every profession starts at the one point for now. */
    private static void writeSpawnPoints(Path dir, int x, int y) throws IOException {
        Files.writeString(dir.resolve("spawnpoints.lua"), "function SpawnPoints()\n    local start = {\n"
            + "        { posX = " + x + ", posY = " + y + ", posZ = 0 },\n    }\n"
            + "    return {\n        unemployed = start,\n    }\nend\n", StandardCharsets.UTF_8);
    }
}
