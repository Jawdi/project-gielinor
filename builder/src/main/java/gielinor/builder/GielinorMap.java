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

    /**
     * The world's surface, in the game's map squares (64 by 64 tiles): the
     * mainland, its islands and the seas around them. Squares beyond it are
     * the game's instanced areas and dungeons.
     */
    static final int FIRST_SQUARE_X = 15;
    static final int LAST_SQUARE_X = 62;
    static final int FIRST_SQUARE_Y = 32;
    static final int LAST_SQUARE_Y = 65;
    /** Each of the game's tiles is this many Zomboid squares across. */
    static final int SCALE = 2;
    /** Where new characters start: the courtyard of Lumbridge castle, in the game's tile coordinates. */
    /**
     * Each chunk's zombie density. Kentucky's own map gives its populated
     * chunks about 2 (and most none, averaging 0.07); every chunk of land
     * here gets 1 for now.
     */
    static final int ZOMBIE_DENSITY = 1;
    /** No zombies within this many squares of where characters start, so they start safe in town. */
    static final int SAFE_RADIUS = 80;

    /**
     * The ground of the built part of the world, one byte a game tile (the
     * {@link GielinorTerrain.Ground}'s ordinal plus one, 0 for none), looked
     * up by Zomboid square. The game's world runs north as y grows and
     * Zomboid's south, so rows are turned over; each game tile is
     * {@link #SCALE} by {@link #SCALE} squares.
     */
    static final class GroundGrid {
        final int width;
        final int height;
        private final int tilesX;
        private final int tilesY;
        private final byte[] tiles;
        /** Each tile's {@link #INDOOR} and {@link #LOADED} bits. */
        private final byte[] flags;
        /** Each level above the ground's tiles (made as they're first set): {@link #FLOOR}, {@link #STONE} and {@link #ROOFED} bits. */
        private final byte[][] upper = new byte[GielinorObjects.LEVELS][];
        static final int FLOOR = 1;
        static final int STONE = 2;
        static final int ROOFED = 4;
        /**
         * Each tile's roofs from the game's roof objects: a bit for each level
         * with a roof (1 << level, levels 1 to 4) and its material in the top
         * three bits (see {@link GielinorRoofs}), made when first set.
         */
        private byte[] roofs;
        private static final int INDOOR = 1;
        private static final int LOADED = 2;

        GroundGrid(int squaresX, int squaresY) {
            tilesX = squaresX * 64;
            tilesY = squaresY * 64;
            tiles = new byte[tilesX * tilesY];
            flags = new byte[tilesX * tilesY];
            width = tilesX * SCALE;
            height = tilesY * SCALE;
        }

        void setTile(int tx, int ty, GielinorTerrain.Ground g) {
            tiles[tx + ty * tilesX] = (byte) (g.ordinal() + 1);
        }

        /** Notes a tile as part of the game's world (its square loaded), and whether it's indoors. */
        void setLoaded(int tx, int ty, boolean indoor) {
            flags[tx + ty * tilesX] = (byte) (LOADED | (indoor ? INDOOR : 0));
        }

        /**
         * Notes a roof on a level over a tile (the game's roof objects stand on
         * the level above what they cover), of a material (an index into
         * {@link GielinorRoofs}), and the tile under it as indoors.
         */
        void roof(int level, int tx, int ty, int material) {
            if (level < 1 || level > 4 || tx < 0 || ty < 0 || tx >= tilesX || ty >= tilesY) {
                return;
            }
            if (roofs == null) {
                roofs = new byte[tilesX * tilesY];
            }
            int i = tx + ty * tilesX;
            roofs[i] = (byte) ((roofs[i] & 0x1f) | 1 << level | material << 5);
            if (level == 1) {
                flags[i] |= INDOOR;
            } else if (level - 1 < GielinorObjects.LEVELS) {
                if (upper[level - 1] == null) {
                    upper[level - 1] = new byte[tilesX * tilesY];
                }
                upper[level - 1][i] |= ROOFED;
            }
        }

        /**
         * Fills the holes in the game's roofs. The game puts its roof
         * objects only on a roof's edges, their slopes rising to meet over
         * the middle, which has none; so on each level, the tiles a roof
         * closes round (not reached from the open without crossing it) are
         * roofed too, in the roof's style, where most of them are inside a
         * building below (a courtyard isn't) or there are only a few.
         * Returns how many tiles were filled.
         */
        int fillRoofHoles() {
            if (roofs == null) {
                return 0;
            }
            int filled = 0;
            int n = tilesX * tilesY;
            int[] queue = new int[n];
            for (int level = 1; level <= 4; level++) {
                int bit = 1 << level;
                // The open: tiles reached from the world's edge without crossing this level's roofs.
                boolean[] open = new boolean[n];
                int tail = 0;
                for (int i = 0; i < n; i++) {
                    int tx = i % tilesX;
                    int ty = i / tilesX;
                    boolean edge = tx == 0 || ty == 0 || tx == tilesX - 1 || ty == tilesY - 1;
                    if (edge && (roofs[i] & bit) == 0) {
                        open[i] = true;
                        queue[tail++] = i;
                    }
                }
                for (int head = 0; head < tail; head++) {
                    int i = queue[head];
                    for (int next : neighbours(i)) {
                        if (next >= 0 && !open[next] && (roofs[next] & bit) == 0) {
                            open[next] = true;
                            queue[tail++] = next;
                        }
                    }
                }
                // Each hole: fill it if it's over a building.
                boolean[] seen = new boolean[n];
                for (int start = 0; start < n; start++) {
                    if (open[start] || seen[start] || (roofs[start] & bit) != 0) {
                        continue;
                    }
                    int head = 0;
                    tail = 0;
                    queue[tail++] = start;
                    seen[start] = true;
                    int inside = 0;
                    int style = -1;
                    while (head < tail) {
                        int i = queue[head++];
                        // Inside a building below, on any level (a hall can rise through two).
                        boolean under = false;
                        for (int below = 0; below < level && !under; below++) {
                            under = indoorTile(below, i % tilesX, i / tilesX) || below > 0 && below < upper.length
                                && upper[below] != null && (upper[below][i] & FLOOR) != 0;
                        }
                        inside += under ? 1 : 0;
                        for (int next : neighbours(i)) {
                            if (next < 0) {
                                continue;
                            }
                            if ((roofs[next] & bit) != 0) {
                                style = style < 0 ? (roofs[next] & 0xff) >> 5 : style;
                            } else if (!seen[next]) {
                                seen[next] = true;
                                queue[tail++] = next;
                            }
                        }
                    }
                    if (inside * 2 >= tail || tail <= 4) {
                        for (int k = 0; k < tail; k++) {
                            roof(level, queue[k] % tilesX, queue[k] / tilesX, Math.max(style, 0));
                        }
                        filled += tail;
                    }
                }
            }
            return filled;
        }

        /**
         * Makes each of the game's roofs one roof. The game builds a tall
         * roof (a church's) as rows of roof objects on planes above one
         * another, each row a plane higher than the one outside it, and a
         * gable roof as rows along its long sides only, open between them;
         * Zomboid's roofs rise from one level. So each roof (roofed tiles side
         * by side, on any level) comes down to its lowest level wherever no
         * floor stands under its higher rows, spreads over its building's
         * roofless inside between its rows, and takes the style most of it
         * has. Returns how many tiles changed.
         */
        int joinRoofs() {
            if (roofs == null) {
                return 0;
            }
            int n = tilesX * tilesY;
            boolean[] seen = new boolean[n];
            int[] queue = new int[n];
            int changed = 0;
            for (int start = 0; start < n; start++) {
                if (seen[start] || lowestRoof(start) < 0) {
                    continue;
                }
                int head = 0;
                int tail = 0;
                queue[tail++] = start;
                seen[start] = true;
                int low = Integer.MAX_VALUE;
                int[] votes = new int[8];
                while (head < tail) {
                    int i = queue[head++];
                    low = Math.min(low, lowestRoof(i));
                    votes[(roofs[i] & 0xff) >> 5]++;
                    for (int next : neighbours(i)) {
                        if (next >= 0 && !seen[next] && lowestRoof(next) >= 0) {
                            seen[next] = true;
                            queue[tail++] = next;
                        }
                    }
                }
                int style = 0;
                for (int k = 1; k < votes.length; k++) {
                    style = votes[k] > votes[style] ? k : style;
                }
                // The building's roofless inside between the roof's rows.
                for (head = 0; head < tail; head++) {
                    for (int next : neighbours(queue[head])) {
                        if (next >= 0 && !seen[next] && lowestRoof(next) < 0 && indoorTile(low - 1, next % tilesX, next / tilesX)
                                && !floorFrom(low, GielinorObjects.LEVELS, next)) {
                            seen[next] = true;
                            queue[tail++] = next;
                        }
                    }
                }
                for (int k = 0; k < tail; k++) {
                    int i = queue[k];
                    int at = lowestRoof(i);
                    int level = at < 0 || !floorFrom(low, at, i) ? low : at;
                    if (level != at || (roofs[i] & 0xff) >> 5 != style) {
                        changed++;
                    }
                    roof(level, i % tilesX, i / tilesX, style);
                }
            }
            return changed;
        }

        /** The lowest level a tile (by its place in the grid) has a roof on, or -1. */
        private int lowestRoof(int i) {
            for (int level = 1; level <= 4; level++) {
                if ((roofs[i] & 1 << level) != 0) {
                    return level;
                }
            }
            return -1;
        }

        /** Whether a tile (by its place in the grid) has a floor on any level from `from` up to (not including) `to`. */
        private boolean floorFrom(int from, int to, int i) {
            for (int level = Math.max(from, 1); level < Math.min(to, upper.length); level++) {
                if (upper[level] != null && (upper[level][i] & FLOOR) != 0) {
                    return true;
                }
            }
            return false;
        }

        /** The four tiles beside one (by its place in the grid), -1 past the world's edge. */
        private int[] neighbours(int i) {
            int tx = i % tilesX;
            int ty = i / tilesX;
            return new int[]{tx > 0 ? i - 1 : -1, tx < tilesX - 1 ? i + 1 : -1, ty > 0 ? i - tilesX : -1, ty < tilesY - 1 ? i + tilesX : -1};
        }

        /** The style of the roof on a level over a Zomboid square (see {@link #roof}), or -1 for none. */
        int roofAt(int level, int zx, int zy) {
            if (roofs == null || zx < 0 || zy < 0 || zx >= width || zy >= height) {
                return -1;
            }
            int r = roofs[zx / SCALE + (tilesY - 1 - zy / SCALE) * tilesX] & 0xff;
            return (r & 1 << level) == 0 ? -1 : r >> 5;
        }

        /** Notes a tile's floor on a level above the ground: {@link #FLOOR}, {@link #STONE} and {@link #ROOFED} bits. */
        void setUpper(int level, int tx, int ty, int bits) {
            if (upper[level] == null) {
                upper[level] = new byte[tilesX * tilesY];
            }
            upper[level][tx + ty * tilesX] |= (byte) bits;
        }

        /** A Zomboid square's floor on a level above the ground (see {@link #setUpper}), 0 for none. */
        int upper(int level, int zx, int zy) {
            if (upper[level] == null || zx < 0 || zy < 0 || zx >= width || zy >= height) {
                return 0;
            }
            return upper[level][zx / SCALE + (tilesY - 1 - zy / SCALE) * tilesX];
        }

        /**
         * Whether one of the game's tiles (by its place in the grid) is inside
         * a building on a level: on the ground, roofed ground; above it, a
         * roofed floor.
         */
        boolean indoorTile(int level, int tx, int ty) {
            if (tx < 0 || ty < 0 || tx >= tilesX || ty >= tilesY) {
                return false;
            }
            int i = tx + ty * tilesX;
            return level == 0 ? (flags[i] & INDOOR) != 0 : level < upper.length && upper[level] != null && (upper[level][i] & ROOFED) != 0;
        }

        /** Notes one of the game's tiles as inside a building on a level (see {@link #indoorTile}). */
        void setIndoorTile(int level, int tx, int ty) {
            if (tx < 0 || ty < 0 || tx >= tilesX || ty >= tilesY) {
                return;
            }
            if (level == 0) {
                flags[tx + ty * tilesX] |= INDOOR;
            } else if (level < upper.length) {
                setUpper(level, tx, ty, FLOOR | ROOFED);
            }
        }

        /** Whether a Zomboid square is indoors (see {@link GielinorTerrain#indoor}). */
        boolean indoor(int zx, int zy) {
            if (zx < 0 || zy < 0 || zx >= width || zy >= height) {
                return false;
            }
            return (flags[zx / SCALE + (tilesY - 1 - zy / SCALE) * tilesX] & INDOOR) != 0;
        }

        /**
         * Fills the gaps in the world's ground (tiles of a loaded square with
         * none: the game's tiles that show only a see-through overlay) with
         * the ground most of their neighbours have, so the world has no holes.
         * Returns how many were filled.
         */
        int fillGaps() {
            int filled = 0;
            for (int pass = 0; pass < 4; pass++) {
                byte[] next = tiles.clone();
                for (int ty = 0; ty < tilesY; ty++) {
                    for (int tx = 0; tx < tilesX; tx++) {
                        int i = tx + ty * tilesX;
                        if (tiles[i] != 0 || (flags[i] & LOADED) == 0) {
                            continue;
                        }
                        int[] votes = new int[GielinorTerrain.Ground.values().length + 1];
                        int best = 0;
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dx = -1; dx <= 1; dx++) {
                                int nx = tx + dx;
                                int ny = ty + dy;
                                if (nx >= 0 && ny >= 0 && nx < tilesX && ny < tilesY && tiles[nx + ny * tilesX] != 0) {
                                    int g = tiles[nx + ny * tilesX];
                                    votes[g]++;
                                    if (votes[g] > votes[best]) {
                                        best = g;
                                    }
                                }
                            }
                        }
                        if (best != 0) {
                            next[i] = (byte) best;
                            filled++;
                        }
                    }
                }
                System.arraycopy(next, 0, tiles, 0, tiles.length);
            }
            return filled;
        }

        /** The ground at a Zomboid square, or null for none. */
        GielinorTerrain.Ground at(int zx, int zy) {
            if (zx < 0 || zy < 0 || zx >= width || zy >= height) {
                return null;
            }
            int b = tiles[zx / SCALE + (tilesY - 1 - zy / SCALE) * tilesX];
            return b == 0 ? null : GielinorTerrain.Ground.values()[b - 1];
        }
    }

    /**
     * Writes the ground of {@link #FIRST_SQUARE_X} to {@link #LAST_SQUARE_Y}:
     * the world's ground is read first, then each cell is made and written
     * in turn, so only one is ever held.
     */
    static void write(Path media, TileDefinitions tiles, GielinorTerrain terrain, GielinorObjects objects) throws IOException {
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
        Map<GielinorTerrain.Ground, Blends> blends = new EnumMap<>(GielinorTerrain.Ground.class);
        for (GielinorTerrain.Ground g : GielinorTerrain.Ground.values()) {
            if (g.layer >= 0) {
                blends.put(g, Blends.of(tiles, g));
            }
        }
        GroundGrid ground = new GroundGrid(LAST_SQUARE_X - FIRST_SQUARE_X + 1, LAST_SQUARE_Y - FIRST_SQUARE_Y + 1);
        int upperFloors = 0;
        Map<GielinorTerrain.Ground, Integer> counts = new EnumMap<>(GielinorTerrain.Ground.class);
        for (int sx = FIRST_SQUARE_X; sx <= LAST_SQUARE_X; sx++) {
            for (int sy = FIRST_SQUARE_Y; sy <= LAST_SQUARE_Y; sy++) {
                MapDefinition square = terrain.square(sx, sy);
                if (square == null) {
                    continue;
                }
                for (int lx = 0; lx < 64; lx++) {
                    for (int ly = 0; ly < 64; ly++) {
                        MapDefinition.Tile tile = GielinorTerrain.tile(square, 0, lx, ly);
                        ground.setLoaded((sx - FIRST_SQUARE_X) * 64 + lx, (sy - FIRST_SQUARE_Y) * 64 + ly, GielinorTerrain.indoor(tile));
                        GielinorTerrain.Ground g = terrain.groundLevel(square, lx, ly);
                        if (g != null) {
                            counts.merge(g, 1, Integer::sum);
                            ground.setTile((sx - FIRST_SQUARE_X) * 64 + lx, (sy - FIRST_SQUARE_Y) * 64 + ly, g);
                        }
                        for (int level = 1; level < GielinorObjects.LEVELS; level++) {
                            int bits = upperFloor(terrain, square, level, lx, ly);
                            if (bits != 0) {
                                ground.setUpper(level, (sx - FIRST_SQUARE_X) * 64 + lx, (sy - FIRST_SQUARE_Y) * 64 + ly, bits);
                                upperFloors++;
                            }
                        }
                    }
                }
            }
        }
        int gaps = ground.fillGaps();
        int width = ground.width;
        int height = ground.height;
        GielinorObjects.Placed[] levels = GielinorObjects.Placed.levels(width, height);
        Main.step("The map: buildings, furniture and scenery");
        objects.place(levels, ground, terrain);
        int roofHoles = ground.fillRoofHoles();
        int roofsJoined = ground.joinRoofs();
        GielinorObjects.Placed placed = levels[0];
        int cellsX = (width + MapCell.SIZE - 1) / MapCell.SIZE;
        int cellsY = (height + MapCell.SIZE - 1) / MapCell.SIZE;
        // Where characters can start: a spot in each town.
        Map<String, int[]> starts = new java.util.LinkedHashMap<>();
        for (Object[] town : TOWNS) {
            int[] spot = startSpot(ground, placed, (Integer) town[1], (Integer) town[2]);
            if (spot == null) {
                System.out.println("  no place to start in " + town[0]);
            } else {
                starts.put((String) town[0], spot);
            }
        }
        int percent = -1;
        for (int cx = 0; cx < cellsX; cx++) {
            for (int cy = 0; cy < cellsY; cy++) {
                int done = 100 * (cx * cellsY + cy) / (cellsX * cellsY);
                if (done != percent) {
                    percent = done;
                    Main.step("The map: cells, " + done + "%");
                }
                MapCell cell = new MapCell(cx, cy);
                GielinorRoofs roofs = new GielinorRoofs(ground, levels, cx * MapCell.SIZE, cy * MapCell.SIZE, MapCell.SIZE);
                for (int x = 0; x < MapCell.SIZE; x++) {
                    for (int y = 0; y < MapCell.SIZE; y++) {
                        int zx = cx * MapCell.SIZE + x;
                        int zy = cy * MapCell.SIZE + y;
                        GielinorTerrain.Ground g = ground.at(zx, zy);
                        List<List<String>> stack = new java.util.ArrayList<>();
                        for (int z = 0; z < MapCell.LEVELS; z++) {
                            stack.add(new java.util.ArrayList<>());
                        }
                        if (g != null) {
                            List<String> choices = floors.get(g);
                            boolean inside = g != GielinorTerrain.Ground.WATER && ground.indoor(zx, zy);
                            boolean stone = g == GielinorTerrain.Ground.STONE;
                            // Indoors a wooden or stone floor; outdoors the ground, the
                            // same square always getting the same of its tiles.
                            stack.get(0).add(inside || g == GielinorTerrain.Ground.DECK ? (stone ? STONE_FLOOR : WOODEN_FLOOR)
                                : choices.get(Math.floorMod(scramble(zx, zy), choices.size())));
                            if (!inside) {
                                blend(stack.get(0), ground, blends, zx, zy);
                            }
                            int bits = g == GielinorTerrain.Ground.WATER ? MapCell.BIT_WATER : inside ? MapCell.BIT_ROOM : 0;
                            bits |= standing(placed, ground, 0, zx, zy, stack.get(0), g != GielinorTerrain.Ground.WATER);
                            cell.setBits(x, y, bits);
                        }
                        for (int z = 1; z < GielinorObjects.LEVELS; z++) {
                            int u = ground.upper(z, zx, zy);
                            if ((u & GroundGrid.FLOOR) != 0 || levels[z].isLanding(zx, zy)) {
                                if (!levels[z].isOpen(zx, zy)) {
                                    stack.get(z).add((u & GroundGrid.STONE) != 0 ? STONE_FLOOR : WOODEN_FLOOR);
                                }
                            }
                            standing(levels[z], ground, z, zx, zy, stack.get(z), true);
                        }
                        roofs.addTo(stack, zx, zy);
                        for (int z = 0; z < MapCell.LEVELS; z++) {
                            if (!stack.get(z).isEmpty()) {
                                cell.set(z, x, y, stack.get(z).toArray(new String[0]));
                            }
                        }
                    }
                }
                // Zombies everywhere but on water and around where characters start.
                for (int kx = 0; kx < MapCell.CHUNKS; kx++) {
                    for (int ky = 0; ky < MapCell.CHUNKS; ky++) {
                        int wet = 0;
                        for (int x = 0; x < MapCell.CHUNK; x++) {
                            for (int y = 0; y < MapCell.CHUNK; y++) {
                                GielinorTerrain.Ground g = ground.at(cx * MapCell.SIZE + kx * MapCell.CHUNK + x,
                                    cy * MapCell.SIZE + ky * MapCell.CHUNK + y);
                                if (g == null || g == GielinorTerrain.Ground.WATER) {
                                    wet++;
                                }
                            }
                        }
                        int middleX = cx * MapCell.SIZE + kx * MapCell.CHUNK + MapCell.CHUNK / 2;
                        int middleY = cy * MapCell.SIZE + ky * MapCell.CHUNK + MapCell.CHUNK / 2;
                        boolean nearStart = false;
                        for (int[] start : starts.values()) {
                            nearStart |= Math.hypot(middleX - start[0], middleY - start[1]) < SAFE_RADIUS;
                        }
                        boolean onWater = wet > MapCell.CHUNK * MapCell.CHUNK / 2;
                        cell.setZombies(kx, ky, nearStart || onWater ? 0 : ZOMBIE_DENSITY);
                    }
                }
                rooms(cell, ground, levels, objects.furniture(), objects.art(), tiles);
                cell.write(dir);
            }
        }
        Main.step("The map: packing the mod's own tiles");
        objects.art().tiles().write(media);
        System.out.println("  own tiles: " + objects.art().tiles().size() + ", " + lanterns + " lanterns and " + containers
            + " containers added to rooms");
        writeInfo(dir);
        Main.step("The world map");
        writeWorldMap(dir, ground, cellsX, cellsY);
        writeTowns(dir, starts);
        Files.writeString(dir.resolve("objects.lua"), "objects = {\n}\n", StandardCharsets.UTF_8);
        System.out.println("  map: " + cellsX * cellsY + " cells, ground " + counts + ", " + gaps + " gaps filled, "
            + roomCount + " rooms in " + buildingCount + " buildings " + roomNames + ", " + upperFloors + " tiles of upper floors, "
            + roofHoles + " tiles of roofs' middles filled, " + roofsJoined + " joined into their roofs, " + starts.size() + " towns to start in");
    }

    /**
     * Adds what stands on a square to its tiles (after its floor): its west
     * and north walls (or the corner of both), any door, gate or window in
     * them, then any tree, bush, plant or rock (not on water). Returns the
     * square's pathfinding bits for them: its walls (not doors or gates, which
     * open) and solid rocks.
     */
    private static int standing(GielinorObjects.Placed placed, GroundGrid ground, int z, int x, int y, List<String> tiles, boolean dry) {
        GielinorObjects.Style west = placed.style(x, y, true);
        GielinorObjects.Style north = placed.style(x, y, false);
        // Above the ground, a wall with no floor either side of it stands on
        // nothing (the game's walls up in its roofs, its gables).
        if (z > 0 && west != null && !floored(placed, ground, z, x - 1, y) && !floored(placed, ground, z, x, y)) {
            west = null;
        }
        if (z > 0 && north != null && !floored(placed, ground, z, x, y - 1) && !floored(placed, ground, z, x, y)) {
            north = null;
        }
        int westGap = placed.gap(x, y, true);
        int northGap = placed.gap(x, y, false);
        int bits = 0;
        if (west != null && west == north && westGap == GielinorObjects.NONE && northGap == GielinorObjects.NONE) {
            tiles.add(west.wall(true, true));
        } else {
            if (west != null) {
                edgeTiles(tiles, west, westGap, true);
            }
            if (north != null) {
                edgeTiles(tiles, north, northGap, false);
            }
        }
        if (west != null && !opens(westGap)) {
            bits |= MapCell.BIT_WALL_WEST;
        }
        if (north != null && !opens(northGap)) {
            bits |= MapCell.BIT_WALL_NORTH;
        }
        String thing = placed.standing(x, y);
        if (thing != null && dry) {
            for (String t : thing.split(java.util.regex.Pattern.quote(GielinorFurniture.STACK))) {
                tiles.add(t);
                if (solid(t)) {
                    bits |= MapCell.BIT_SOLID;
                }
            }
        }
        return bits;
    }

    /**
     * A ground's blend overlays (see {@link TileDefinitions#blends}): the
     * half of a square at each corner (north-west, north-east, south-west,
     * south-east) and the fringe along each edge (north, west, east, south),
     * each in its variants.
     */
    private record Blends(List<List<String>> corners, List<List<String>> edges) {
        static Blends of(TileDefinitions tiles, GielinorTerrain.Ground g) throws IOException {
            List<List<String>> corners = new java.util.ArrayList<>();
            for (String c : new String[]{"NW", "NE", "SW", "SE"}) {
                corners.add(tiles.blends(g.sheet, g.material, c));
            }
            List<List<String>> edges = new java.util.ArrayList<>();
            for (String e : new String[]{"N", "W", "E", "S"}) {
                edges.add(tiles.blends(g.sheet, g.material, e));
            }
            for (List<String> l : corners) {
                if (l.isEmpty()) {
                    throw new IOException("No " + g.material + " blends in Zomboid's tile definitions");
                }
            }
            return new Blends(corners, edges);
        }
    }

    /**
     * Laps the ground of a square's neighbours over it where theirs is the
     * higher layer, as Zomboid's own maps blend their ground: each of the
     * game's tiles is two squares a side, so a square has two neighbours
     * outside its tile, one across its north or south edge and one across
     * its west or east edge. Where both are the same ground, that ground
     * covers the half of the square at their corner (so the game's
     * staircase of tiles along a diagonal path becomes a diagonal edge);
     * else each laps a fringe over its edge.
     */
    private static void blend(List<String> tiles, GroundGrid ground, Map<GielinorTerrain.Ground, Blends> blends, int zx, int zy) {
        GielinorTerrain.Ground here = blendable(ground, zx, zy);
        if (here == null) {
            return;
        }
        boolean north = zy % SCALE == 0;
        boolean west = zx % SCALE == 0;
        GielinorTerrain.Ground across = blendable(ground, zx, north ? zy - 1 : zy + 1);
        GielinorTerrain.Ground beside = blendable(ground, west ? zx - 1 : zx + 1, zy);
        across = across != null && across.layer > here.layer ? across : null;
        beside = beside != null && beside.layer > here.layer ? beside : null;
        int hash = scramble(zx * 7 + 3, zy * 5 + 1);
        if (across != null && across == beside) {
            int corner = (north ? 0 : 2) + (west ? 0 : 1);
            tiles.add(pick(blends.get(across).corners.get(corner), hash));
            return;
        }
        // The lower layer first, so the higher laps over it.
        GielinorTerrain.Ground[] order = across != null && beside != null && beside.layer < across.layer
            ? new GielinorTerrain.Ground[]{beside, across} : new GielinorTerrain.Ground[]{across, beside};
        for (GielinorTerrain.Ground g : order) {
            if (g != null) {
                int edge = g == across ? (north ? 0 : 3) : (west ? 1 : 2);
                tiles.add(pick(blends.get(g).edges.get(edge), hash));
            }
        }
    }

    /** A square's ground where it can blend: outdoors, and not a bridge's deck; else null. */
    private static GielinorTerrain.Ground blendable(GroundGrid ground, int zx, int zy) {
        GielinorTerrain.Ground g = ground.at(zx, zy);
        return g == null || g.layer < 0 || g != GielinorTerrain.Ground.WATER && ground.indoor(zx, zy) ? null : g;
    }

    private static String pick(List<String> choices, int hash) {
        return choices.get(Math.floorMod(hash, choices.size()));
    }

    /** Whether a square on a level above the ground has a floor (or the stairs' hole in one). */
    private static boolean floored(GielinorObjects.Placed placed, GroundGrid ground, int z, int x, int y) {
        return (ground.upper(z, x, y) & GroundGrid.FLOOR) != 0 || placed.isLanding(x, y) || placed.isOpen(x, y);
    }

    /** Whether something standing on a square blocks the way: a large boulder or large ore rock. */
    private static boolean solid(String tile) {
        int i = Integer.parseInt(tile.substring(tile.lastIndexOf('_') + 1));
        return tile.startsWith("boulders_") && i >= 16
            || tile.startsWith("crafting_ore_") && (i >= 6 && i <= 8 || i >= 22 && i <= 24);
    }

    /** Whether an edge's gap opens (a door or a gate), so isn't a wall to find a way past. */
    private static boolean opens(int gap) {
        return gap == GielinorObjects.DOOR || gap == GielinorObjects.GATE;
    }

    /** A wall's tiles along one edge: the wall, or its frame and what's in it. */
    private static void edgeTiles(List<String> tiles, GielinorObjects.Style style, int gap, boolean west) {
        boolean building = style.building();
        if (gap == GielinorObjects.GATE || gap == GielinorObjects.DOOR && !building) {
            tiles.add(style.gate(west));
        } else if (gap == GielinorObjects.DOOR) {
            tiles.add(style.frame(west, true));
            tiles.add(west ? "fixtures_doors_01_8" : "fixtures_doors_01_9");
        } else if (gap == GielinorObjects.WINDOW && building) {
            tiles.add(style.frame(west, false));
            tiles.add(west ? "fixtures_windows_01_0" : "fixtures_windows_01_1");
        } else {
            tiles.add(style.wall(west, !west));
        }
    }

    /** Indoor floors (Zomboid's tiles): planks and flagstones. */
    private static final String WOODEN_FLOOR = "floors_interior_tilesandwood_01_41";
    private static final String STONE_FLOOR = "floors_exterior_tilesandstone_01_5";
    private static int roomCount;
    private static int buildingCount;
    private static final Map<String, Integer> roomNames = new java.util.TreeMap<>();

    /**
     * The bits a level above the ground has at a tile (see
     * {@link GroundGrid#setUpper}): a floor where the game paints one there
     * (an overlay, as its upper storeys are painted, or an underlay under a
     * roof), of stone or wood by its look, and whether it's roofed. The tile
     * the game draws there: the level's own, or the level above's where the
     * game draws that a level down (see {@link GielinorTerrain#drawnHere}).
     */
    private static int upperFloor(GielinorTerrain terrain, MapDefinition square, int level, int x, int y) {
        MapDefinition.Tile own = GielinorTerrain.tile(square, level, x, y);
        MapDefinition.Tile above = GielinorTerrain.tile(square, level + 1, x, y);
        int bits = GielinorTerrain.drawnHere(own) ? floorBits(terrain, own) : 0;
        return bits == 0 && GielinorTerrain.drawnBelow(above) ? floorBits(terrain, above) : bits;
    }

    private static int floorBits(GielinorTerrain terrain, MapDefinition.Tile tile) {
        if (tile == null) {
            return 0;
        }
        boolean roofed = GielinorTerrain.indoor(tile);
        boolean painted = (tile.getOverlayId() & 0xffff) > 0 || roofed && (tile.getUnderlayId() & 0xffff) > 0;
        GielinorTerrain.Ground g = painted ? terrain.ground(tile) : null;
        if (g == null || g == GielinorTerrain.Ground.WATER) {
            return 0;
        }
        return GroundGrid.FLOOR | (g == GielinorTerrain.Ground.STONE ? GroundGrid.STONE : 0) | (roofed ? GroundGrid.ROOFED : 0);
    }

    /**
     * The cell's rooms and buildings: on each level, a room is the indoor
     * squares reached from one another without crossing a wall (or a door),
     * written as rectangles and named by its furniture (Zomboid fills its
     * containers by its name); a building the rooms whose squares touch,
     * walls or not, and those above and below one another. A building across
     * a cell's edge is one in each cell.
     */
    private static void rooms(MapCell cell, GroundGrid ground, GielinorObjects.Placed[] levels, GielinorFurniture furniture,
            GielinorArt art, TileDefinitions tiles) throws IOException {
        int size = MapCell.SIZE;
        int x0 = cell.cellX * size;
        int y0 = cell.cellY * size;
        int[][] room = new int[GielinorObjects.LEVELS][size * size];
        int[] queue = new int[size * size];
        int rooms = 0;
        for (int z = 0; z < GielinorObjects.LEVELS; z++) {
            int[] here = room[z];
            java.util.Arrays.fill(here, -1);
            for (int start = 0; start < size * size; start++) {
                if (here[start] != -1 || !indoor(ground, levels, z, x0 + start % size, y0 + start / size)) {
                    continue;
                }
                List<Integer> squares = new java.util.ArrayList<>();
                int head = 0;
                int tail = 0;
                queue[tail++] = start;
                here[start] = -2;
                while (head < tail) {
                    int i = queue[head++];
                    squares.add(i);
                    int x = i % size;
                    int y = i / size;
                    for (int[] step : STEPS) {
                        int nx = x + step[0];
                        int ny = y + step[1];
                        if (nx < 0 || ny < 0 || nx >= size || ny >= size || here[nx + ny * size] != -1
                                || !indoor(ground, levels, z, x0 + nx, y0 + ny) || walled(levels[z], x0 + x, y0 + y, x0 + nx, y0 + ny)) {
                            continue;
                        }
                        here[nx + ny * size] = -2;
                        queue[tail++] = nx + ny * size;
                    }
                }
                List<String> things = new java.util.ArrayList<>();
                for (int i : squares) {
                    things.add(levels[z].standing(x0 + i % size, y0 + i / size));
                }
                String name = furniture.roomName(things);
                roomNames.merge(name, 1, Integer::sum);
                int id = cell.addRoom(name, z, rectangles(squares, size));
                List<int[]> walls = wallSquares(levels[z], squares, size, x0, y0);
                light(cell, art, z, walls, things, squares, size);
                stock(cell, furniture, tiles, levels[z], name, z, walls, things, squares, size, x0, y0);
                for (int i : squares) {
                    here[i] = id;
                }
                rooms++;
            }
        }
        roomCount += rooms;
        // The buildings: rooms touching side by side, or one over another.
        int[] parent = new int[rooms];
        for (int i = 0; i < rooms; i++) {
            parent[i] = i;
        }
        for (int z = 0; z < GielinorObjects.LEVELS; z++) {
            for (int i = 0; i < size * size; i++) {
                int r = room[z][i];
                if (r < 0) {
                    continue;
                }
                if (i % size + 1 < size && room[z][i + 1] >= 0) {
                    join(parent, r, room[z][i + 1]);
                }
                if (i + size < size * size && room[z][i + size] >= 0) {
                    join(parent, r, room[z][i + size]);
                }
                if (z > 0 && room[z - 1][i] >= 0) {
                    join(parent, r, room[z - 1][i]);
                }
            }
        }
        Map<Integer, List<Integer>> buildings = new java.util.TreeMap<>();
        for (int r = 0; r < rooms; r++) {
            buildings.computeIfAbsent(root(parent, r), k -> new java.util.ArrayList<>()).add(r);
        }
        for (List<Integer> b : buildings.values()) {
            cell.addBuilding(b);
            buildingCount++;
        }
    }

    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** A light is hung where none is nearer than this many squares (the lights shine seven). */
    private static final int LIGHT_SPACING = 5;
    /** A room should have a container for each of these squares of it, and one at least from a sixth as many. */
    private static final int SQUARES_A_CONTAINER = 30;
    private static int lanterns;
    private static int containers;

    /**
     * A room's squares against a wall (of a building, and no door, window or
     * gate in it), in a scattered order: each the square (by its place in the
     * cell) and the side its wall is on (0 west, 1 north, 2 east, 3 south).
     */
    private static List<int[]> wallSquares(GielinorObjects.Placed placed, List<Integer> squares, int size, int x0, int y0) {
        List<int[]> out = new java.util.ArrayList<>();
        for (int i : squares) {
            int zx = x0 + i % size;
            int zy = y0 + i / size;
            int[][] edges = {{zx, zy, 1}, {zx, zy, 0}, {zx + 1, zy, 1}, {zx, zy + 1, 0}};
            for (int side = 0; side < 4; side++) {
                int[] e = edges[side];
                GielinorObjects.Style style = placed.style(e[0], e[1], e[2] == 1);
                if (style != null && style.building() && placed.gap(e[0], e[1], e[2] == 1) == GielinorObjects.NONE) {
                    out.add(new int[]{i, side});
                }
            }
        }
        out.sort(java.util.Comparator.comparingInt(w -> scramble(x0 + w[0] % size, y0 + w[0] / size + w[1] * 7)));
        return out;
    }

    /**
     * Hangs lanterns on a room's walls where the game leaves it dark: going
     * round its walls (see {@link #wallSquares}), each square takes one
     * unless a light (the game's own or one already hung) is within
     * {@link #LIGHT_SPACING} squares.
     */
    private static void light(MapCell cell, GielinorArt art, int z, List<int[]> walls, List<String> things, List<Integer> squares,
            int size) throws IOException {
        List<Integer> lit = new java.util.ArrayList<>();
        for (int k = 0; k < squares.size(); k++) {
            String stacked = things.get(k);
            for (String t : stacked == null ? new String[0] : stacked.split(java.util.regex.Pattern.quote(GielinorFurniture.STACK))) {
                if (art.isLight(t)) {
                    lit.add(squares.get(k));
                }
            }
        }
        for (int[] w : walls) {
            int x = w[0] % size;
            int y = w[0] / size;
            boolean near = false;
            for (int l : lit) {
                near |= Math.max(Math.abs(l % size - x), Math.abs(l / size - y)) <= LIGHT_SPACING;
            }
            if (near) {
                continue;
            }
            String lantern = art.lantern(w[1]);
            if (lantern != null) {
                cell.add(z, x, y, lantern);
                lit.add(w[0]);
                lanterns++;
            }
        }
    }

    /** Whether a square has a door, gate or window on any of its edges (nothing should stand in the way). */
    private static boolean doorway(GielinorObjects.Placed placed, int zx, int zy) {
        return placed.gap(zx, zy, true) != GielinorObjects.NONE || placed.gap(zx, zy, false) != GielinorObjects.NONE
            || placed.gap(zx + 1, zy, true) != GielinorObjects.NONE || placed.gap(zx, zy + 1, false) != GielinorObjects.NONE;
    }

    /**
     * Gives a room the containers it lacks (its items' places, filled by
     * Zomboid as the room's name has it): one for each
     * {@link #SQUARES_A_CONTAINER} squares, against walls where nothing
     * stands, facing into the room.
     */
    private static void stock(MapCell cell, GielinorFurniture furniture, TileDefinitions tiles, GielinorObjects.Placed placed,
            String name, int z, List<int[]> walls, List<String> things, List<Integer> squares, int size, int x0, int y0) {
        int have = 0;
        for (String stacked : things) {
            for (String t : stacked == null ? new String[0] : stacked.split(java.util.regex.Pattern.quote(GielinorFurniture.STACK))) {
                if (tiles.tiles.getOrDefault(t, Map.of()).containsKey("container")) {
                    have++;
                }
            }
        }
        int want = squares.size() < SQUARES_A_CONTAINER / 6 ? 0 : Math.max(1, squares.size() / SQUARES_A_CONTAINER);
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (int[] w : walls) {
            if (have >= want) {
                break;
            }
            int zx = x0 + w[0] % size;
            int zy = y0 + w[0] / size;
            if (used.contains(w[0]) || placed.standing(zx, zy) != null || placed.isOpen(zx, zy) || placed.isLanding(zx, zy)
                    || doorway(placed, zx, zy)) {
                continue;
            }
            String tile = furniture.container(name, "ESWN".charAt(w[1]), scramble(zx, zy));
            if (tile != null) {
                cell.add(z, w[0] % size, w[0] / size, tile);
                used.add(w[0]);
                have++;
                containers++;
            }
        }
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void join(int[] parent, int a, int b) {
        parent[root(parent, a)] = root(parent, b);
    }

    /** Whether a square on a level is indoors: on the ground, roofed ground; above it, a roofed floor (not over stairs). */
    private static boolean indoor(GroundGrid ground, GielinorObjects.Placed[] levels, int z, int zx, int zy) {
        if (z == 0) {
            GielinorTerrain.Ground g = ground.at(zx, zy);
            return g != null && g != GielinorTerrain.Ground.WATER && ground.indoor(zx, zy);
        }
        int u = ground.upper(z, zx, zy);
        return (u & GroundGrid.FLOOR) != 0 && (u & GroundGrid.ROOFED) != 0 && !levels[z].isOpen(zx, zy);
    }

    /** Whether a wall (or door, or gate) stands between two squares side by side. */
    private static boolean walled(GielinorObjects.Placed placed, int ax, int ay, int bx, int by) {
        int x = Math.max(ax, bx);
        int y = Math.max(ay, by);
        boolean west = ax != bx;
        return placed.inside(x, y) && (placed.style(x, y, west) != null || placed.gap(x, y, west) != GielinorObjects.NONE);
    }

    /** A room's squares (by x + y * size) as rectangles (x, y, width, height): runs along each row, stacked while the rows below match. */
    private static List<int[]> rectangles(List<Integer> squares, int size) {
        java.util.Set<Integer> left = new java.util.HashSet<>(squares);
        List<int[]> out = new java.util.ArrayList<>();
        List<Integer> sorted = new java.util.ArrayList<>(squares);
        sorted.sort(Integer::compare);
        for (int i : sorted) {
            if (!left.contains(i)) {
                continue;
            }
            int x = i % size;
            int y = i / size;
            int right = x;
            while (right + 1 < size && left.contains(right + 1 + y * size)) {
                right++;
            }
            int bottom = y;
            outer:
            while (bottom + 1 < size) {
                for (int rx = x; rx <= right; rx++) {
                    if (!left.contains(rx + (bottom + 1) * size)) {
                        break outer;
                    }
                }
                bottom++;
            }
            for (int ry = y; ry <= bottom; ry++) {
                for (int rx = x; rx <= right; rx++) {
                    left.remove(rx + ry * size);
                }
            }
            out.add(new int[]{x, y, right - x + 1, bottom - y + 1});
        }
        return out;
    }

    /** A number that looks random but is always the same for a square (a 64-bit mixing function). */
    static int scramble(int x, int y) {
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
            case DIRT, DECK -> new String[]{"gielinor", "dirt"};
            default -> null;
        };
    }

    /**
     * Writes the map Zomboid shows (the M key, and the maps found as loot):
     * per cell, its ground and its buildings as rectangles of one feature
     * each, made by running along each row and stacking equal runs of the
     * rows below onto them.
     * Points are in squares from the cell's corner.
     *
     * The data goes in `worldmap.xml.bin`, the binary form the game's own
     * maps ship: Zomboid's reader of the XML form miscounts each polygon's
     * points (as twice as many) and fails on every one. The map screen only
     * looks for data where a `worldmap.xml` exists, so an empty one stands
     * beside it.
     */
    private static void writeWorldMap(Path dir, GroundGrid ground, int cellsX, int cellsY) throws IOException {
        int width = ground.width;
        int height = ground.height;
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
                        GielinorTerrain.Ground g = ground.at(x, y);
                        String[] feature = g == null ? null : mapFeature(g);
                        if (feature == null || done[x - x0][y - y0]) {
                            continue;
                        }
                        int right = x;
                        while (right + 1 < x1 && ground.at(right + 1, y) == g && !done[right + 1 - x0][y - y0]) {
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
                // The buildings, as the map draws them: their indoor squares,
                // in rectangles the same way, kept whatever specks of ground go.
                List<int[]> buildings = new java.util.ArrayList<>();
                boolean[][] roofed = new boolean[MapCell.SIZE][MapCell.SIZE];
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        if (roofed[x - x0][y - y0] || !ground.indoor(x, y)) {
                            continue;
                        }
                        int right = x;
                        while (right + 1 < x1 && ground.indoor(right + 1, y) && !roofed[right + 1 - x0][y - y0]) {
                            right++;
                        }
                        int bottom = y;
                        outer:
                        while (bottom + 1 < y1) {
                            for (int rx = x; rx <= right; rx++) {
                                if (!ground.indoor(rx, bottom + 1) || roofed[rx - x0][bottom + 1 - y0]) {
                                    break outer;
                                }
                            }
                            bottom++;
                        }
                        for (int ry = y; ry <= bottom; ry++) {
                            for (int rx = x; rx <= right; rx++) {
                                roofed[rx - x0][ry - y0] = true;
                            }
                        }
                        buildings.add(new int[]{x - x0, y - y0, right + 1 - x0, bottom + 1 - y0});
                    }
                }
                if (rects.isEmpty() && buildings.isEmpty()) {
                    writeInt(bytes, -1);
                    continue;
                }
                if (rects.size() > MAX_FEATURES - buildings.size()) {
                    // Keep the largest; the smallest specks go.
                    Integer[] order = new Integer[rects.size()];
                    for (int i = 0; i < order.length; i++) {
                        order[i] = i;
                    }
                    List<int[]> all = rects;
                    java.util.Arrays.sort(order, java.util.Comparator.comparingInt(i -> -area(all.get(i))));
                    List<int[]> keptRects = new java.util.ArrayList<>();
                    List<String[]> keptKinds = new java.util.ArrayList<>();
                    for (int i = 0; i < MAX_FEATURES - buildings.size(); i++) {
                        keptRects.add(rects.get(order[i]));
                        keptKinds.add(kinds.get(order[i]));
                    }
                    rects = keptRects;
                    kinds = keptKinds;
                }
                for (int[] b : buildings) {
                    rects.add(b);
                    kinds.add(new String[]{"building", "yes"});
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

    /**
     * The most features a cell of the map may have. Zomboid keeps where each
     * feature's points and triangles start as 16-bit numbers, which a cell
     * of over about 4,000 rectangles would overflow.
     */
    private static final int MAX_FEATURES = 4000;

    private static int area(int[] r) {
        return (r[2] - r[0]) * (r[3] - r[1]);
    }

    /** Whether row `y` from `x` to `right` is all ground `g` and not yet drawn. */
    private static boolean sameRun(GroundGrid ground, boolean[][] done, int x0, int y0, int x, int right, int y,
            GielinorTerrain.Ground g) {
        for (int rx = x; rx <= right; rx++) {
            if (ground.at(rx, y) != g || done[rx - x0][y - y0]) {
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

    /**
     * The towns a game can start in, as the game's own maps name them: where
     * its teleports land, or else where its world map writes the town's name.
     */
    private static final Object[][] TOWNS = {
        {"Lumbridge", 3222, 3218}, {"Draynor Village", 3105, 3251}, {"Varrock", 3212, 3423}, {"Edgeville", 3087, 3503},
        {"Barbarian Village", 3063, 3416}, {"Al Kharid", 3293, 3163}, {"Falador", 2965, 3380}, {"Port Sarim", 3047, 3221},
        {"Rimmington", 2960, 3222}, {"Taverley", 2900, 3458}, {"Burthorpe", 2900, 3552}, {"Catherby", 2804, 3433},
        {"Seers' Village", 2692, 3489}, {"East Ardougne", 2662, 3307}, {"West Ardougne", 2524, 3308}, {"Yanille", 2549, 3091},
        {"Tree Gnome Stronghold", 2433, 3454}, {"Port Khazard", 2659, 3189}, {"Brimhaven", 2779, 3174}, {"Musa Point", 2918, 3176},
        {"Shilo Village", 2849, 2986}, {"Canifis", 3496, 3489}, {"Port Phasmatys", 3673, 3487}, {"Rellekka", 2672, 3681},
        {"Pollnivneach", 3356, 2980}, {"Nardah", 3429, 2902}, {"Sophanem", 3298, 2783}, {"Hosidius", 1750, 3607},
        {"Arceuus", 1678, 3752}, {"Lovakengj", 1503, 3798}, {"Shayzien", 1536, 3552}, {"Port Piscarilius", 1824, 3704},
        {"Civitas illa Fortis", 1713, 3127}, {"Prifddinas", 2240, 3328}, {"Lletya", 2346, 3180},
    };
    /** How far from a town's point (in squares) a spot to start on is looked for. */
    private static final int TOWN_REACH = 40;

    /**
     * The towns to start in, as Zomboid's own towns are laid out: each a map
     * folder beside the world's (`Gielinor, Varrock`) with a `map.info`
     * naming it and pointing at the world's lots, and its spot to start on
     * in its `spawnpoints.lua`. The world's folder lists them all in
     * `spawnregions.lua`, for a game (a server's) that names only the world.
     */
    private static void writeTowns(Path dir, Map<String, int[]> starts) throws IOException {
        Path maps = dir.getParent();
        try (var old = Files.list(maps)) {
            for (Path p : old.filter(p -> p.getFileName().toString().startsWith(FOLDER + ", ")).toList()) {
                deleteTree(p);
            }
        }
        StringBuilder regions = new StringBuilder("function SpawnRegions()\n    return {\n");
        for (Map.Entry<String, int[]> town : starts.entrySet()) {
            String name = town.getKey();
            int[] spot = town.getValue();
            String folder = FOLDER + ", " + name.replace("'", "");
            Path townDir = maps.resolve(folder);
            Files.createDirectories(townDir);
            Files.writeString(townDir.resolve("map.info"), String.join("\n",
                "title=" + name,
                "lots=" + FOLDER,
                "fixed2x=true",
                "description=Start in " + name + ".",
                "zoomX=" + spot[0],
                "zoomY=" + spot[1],
                "zoomS=14.5",
                ""), StandardCharsets.UTF_8);
            Files.writeString(townDir.resolve("spawnpoints.lua"), "function SpawnPoints()\n    local start = {\n"
                + "        { posX = " + spot[0] + ", posY = " + spot[1] + ", posZ = 0 },\n    }\n"
                + "    return {\n        unemployed = start,\n    }\nend\n", StandardCharsets.UTF_8);
            regions.append("        { name = \"").append(folder).append("\", file = \"media/maps/").append(folder).append("/spawnpoints.lua\" },\n");
        }
        Files.writeString(dir.resolve("spawnregions.lua"), regions.append("    }\nend\n").toString(), StandardCharsets.UTF_8);
    }

    /**
     * Where to start in a town: the square nearest its point (a game tile),
     * within {@link #TOWN_REACH}, that's dry, outdoors and empty; null if
     * there's none (the town is left out of the map).
     */
    private static int[] startSpot(GroundGrid ground, GielinorObjects.Placed placed, int x, int y) {
        int zx = (x - FIRST_SQUARE_X * 64) * SCALE;
        int zy = ground.height - (y - FIRST_SQUARE_Y * 64 + 1) * SCALE;
        for (int r = 0; r <= TOWN_REACH; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) {
                        continue;
                    }
                    int sx = zx + dx;
                    int sy = zy + dy;
                    GielinorTerrain.Ground g = ground.at(sx, sy);
                    if (g != null && g != GielinorTerrain.Ground.WATER && !ground.indoor(sx, sy) && placed.inside(sx, sy)
                            && placed.standing(sx, sy) == null) {
                        return new int[]{sx, sy};
                    }
                }
            }
        }
        return null;
    }
}
