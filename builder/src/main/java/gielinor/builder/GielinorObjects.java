package gielinor.builder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.cache.EntityOpsDefinition;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.definitions.LocationsDefinition;
import net.runelite.cache.definitions.MapDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.RegionLoader;

/**
 * The things standing in the game's world: walls, fences, doors, gates and
 * windows along the edges of its tiles, stairs and furniture (see
 * {@link GielinorFurniture}) on every floor, and trees, bushes, plants and
 * rocks on its ground, each as Zomboid's own (so its doors open,
 * its trees are chopped and grow through the seasons, its fences are
 * climbed). Read from the game's map squares (each square's objects are
 * locked with a key, see {@link Pinned#keys}).
 *
 * <p>The game's walls are mostly unnamed, so a wall is sorted by its model:
 * walls that don't block are left out (archways), as are walls with nothing
 * to see (the invisible lines the game fences cliffs and edges with). A wall
 * that doesn't stop arrows (the game's own mark of a fence) or that's mostly
 * gaps is a wooden fence if it's coloured like the game's wood (warm and
 * strong), else a low stone wall if it's fairly solid (a dry stone wall) or
 * an iron railing; the rest are buildings' walls, of stone, plaster, brick or
 * wood by colour, with a window where the model has an opening halfway up
 * (wall below and above it).
 *
 * <p>A door is placed where it closes. The game stores some doors open,
 * swung out a tile from their doorway and turned a quarter; those are turned
 * back into their doorway (as the game turns them, a single door one way and
 * a leaf of a double door the other, whichever lands in the wall's gap). A
 * tile is two Zomboid squares across: a single door takes one of them and
 * the wall the other; two doors side by side are a double door across four,
 * as Zomboid's own double gates are. Some of the game's doors have no name
 * and can't be opened, a castle's doors standing open for good: a nameless
 * piece of wall standing alone that would swing shut into a doorway is one.
 */
final class GielinorObjects {
    /** A wall's look: which of Zomboid's wall tiles it's made of. */
    enum Style {
        STONE("walls_exterior_house_02", 0),
        GREY_STONE("walls_exterior_house_02", 16),
        PLASTER("walls_exterior_house_02", 48),
        BRICK("walls_exterior_house_02", 64),
        WOOD("walls_exterior_wooden_01", 0),
        LOGS("walls_logs", 0),
        LOW_WALL("walls_exterior_house_low_01", 0),
        FENCE("crafted_02", -1),
        RAILING("fencing_01", -1);

        final String sheet;
        final int base;

        Style(String sheet, int base) {
            this.sheet = sheet;
            this.base = base;
        }

        /** Whether it's a building's wall, with windows and door frames. */
        boolean building() {
            return this != LOW_WALL && this != FENCE && this != RAILING;
        }

        /** The west wall, north wall or both (a corner), as a tile. */
        String wall(boolean west, boolean north) {
            int corner = west && north ? 2 : west ? 0 : 1;
            return switch (this) {
                case FENCE -> "crafted_02_" + new int[]{110, 109, 111}[corner];
                case RAILING -> "fencing_01_" + new int[]{2, 1, 3}[corner];
                default -> sheet + "_" + (base + corner);
            };
        }

        /** A window's or door's frame in the wall, as a tile (buildings only). */
        String frame(boolean west, boolean door) {
            return sheet + "_" + (base + (door ? 10 : 8) + (west ? 0 : 1));
        }

        /** A single gate in it, as a tile: iron in a railing, else wooden. */
        String gate(boolean west) {
            return "fixtures_doors_fences_01_" + (this == RAILING ? (west ? 0 : 1) : (west ? 4 : 5));
        }

        /** One of the four pieces (1 to 4) of a double gate in it, as a tile: iron in a railing, else wooden. */
        String doubleGate(boolean west, int piece) {
            int[] pieces = this == RAILING
                ? (west ? new int[]{40, 41, 32, 33} : new int[]{34, 35, 42, 43})
                : (west ? new int[]{56, 57, 48, 49} : new int[]{50, 51, 58, 59});
            return "fixtures_doors_fences_01_" + pieces[piece - 1];
        }
    }

    /** What's in a square's edge: nothing, a door, a window, a gate, or a double gate's piece 1 to 4 ({@link #DOUBLE_GATE} plus the piece less one). */
    static final int NONE = 0;
    static final int DOOR = 1;
    static final int WINDOW = 2;
    static final int GATE = 3;
    static final int DOUBLE_GATE = 4;

    /** Zomboid's tiles for the things standing on tiles. */
    private static final int[] TREES = {8, 9, 10, 11, 13, 14, 15, 17, 24, 25};
    private static final int[] BUSHES = {0, 1, 2, 3, 4, 5, 8, 9, 10, 11, 12, 13, 14};
    private static final int[] GROUND_PLANTS = {16, 17, 18, 19, 20, 21, 22, 23};
    private static final int[] STONES = {8, 9, 10, 11, 12, 13, 14, 15};

    /** The floors of the game's world, as Zomboid's levels: the ground and three above. */
    static final int LEVELS = 4;

    /**
     * Everything placed on one level, by Zomboid square (as
     * {@link GielinorMap.GroundGrid} lays the world out): each square's west
     * and north edge (a style's ordinal plus one, and what's in its gap) and
     * what stands on it (a tile, by its place in a list shared by all
     * levels). Kept in blocks made as they're first written, as the levels
     * above the ground are mostly empty.
     */
    static final class Placed {
        private static final int BLOCK = 64;
        final int width;
        final int height;
        private final int blocksX;
        private final Block[] blocks;
        private final List<String> tiles;
        private final Map<String, Short> tileIndex;

        private static final class Block {
            final byte[] westStyle = new byte[BLOCK * BLOCK];
            final byte[] northStyle = new byte[BLOCK * BLOCK];
            final byte[] westGap = new byte[BLOCK * BLOCK];
            final byte[] northGap = new byte[BLOCK * BLOCK];
            final short[] standing = new short[BLOCK * BLOCK];
            /** Squares left without a floor, over stairs below. */
            final boolean[] open = new boolean[BLOCK * BLOCK];
            /** Squares given a floor, where stairs from below come out. */
            final boolean[] landing = new boolean[BLOCK * BLOCK];
        }

        private Placed(int width, int height, List<String> tiles, Map<String, Short> tileIndex) {
            this.width = width;
            this.height = height;
            this.blocksX = (width + BLOCK - 1) / BLOCK;
            this.blocks = new Block[blocksX * ((height + BLOCK - 1) / BLOCK)];
            this.tiles = tiles;
            this.tileIndex = tileIndex;
        }

        /** {@link #LEVELS} levels sharing one list of tiles. */
        static Placed[] levels(int width, int height) {
            List<String> tiles = new ArrayList<>(List.of(""));
            Map<String, Short> index = new HashMap<>();
            Placed[] out = new Placed[LEVELS];
            for (int z = 0; z < LEVELS; z++) {
                out[z] = new Placed(width, height, tiles, index);
            }
            return out;
        }

        boolean inside(int x, int y) {
            return x >= 0 && y >= 0 && x < width && y < height;
        }

        private Block block(int x, int y, boolean make) {
            int i = x / BLOCK + y / BLOCK * blocksX;
            if (blocks[i] == null && make) {
                blocks[i] = new Block();
            }
            return blocks[i];
        }

        private static int at(int x, int y) {
            return x % BLOCK + y % BLOCK * BLOCK;
        }

        void edge(int x, int y, boolean west, Style style, int gap) {
            if (!inside(x, y)) {
                return;
            }
            Block b = block(x, y, true);
            (west ? b.westStyle : b.northStyle)[at(x, y)] = (byte) (style == null ? 0 : style.ordinal() + 1);
            (west ? b.westGap : b.northGap)[at(x, y)] = (byte) gap;
        }

        Style style(int x, int y, boolean west) {
            Block b = inside(x, y) ? block(x, y, false) : null;
            int s = b == null ? 0 : (west ? b.westStyle : b.northStyle)[at(x, y)];
            return s == 0 ? null : Style.values()[s - 1];
        }

        int gap(int x, int y, boolean west) {
            Block b = inside(x, y) ? block(x, y, false) : null;
            return b == null ? NONE : (west ? b.westGap : b.northGap)[at(x, y)];
        }

        void stand(int x, int y, String tile) {
            if (!inside(x, y)) {
                return;
            }
            short t = tileIndex.computeIfAbsent(tile, k -> {
                tiles.add(k);
                return (short) (tiles.size() - 1);
            });
            block(x, y, true).standing[at(x, y)] = t;
        }

        /** Leaves a square without a floor (it's over stairs coming up from below). */
        void open(int x, int y) {
            if (inside(x, y)) {
                block(x, y, true).open[at(x, y)] = true;
            }
        }

        /** Gives a square a floor, whatever the game has there (stairs from below come out onto it). */
        void land(int x, int y) {
            if (inside(x, y)) {
                block(x, y, true).landing[at(x, y)] = true;
            }
        }

        /** Whether stairs from below come out onto a square (see {@link #land}). */
        boolean isLanding(int x, int y) {
            Block b = inside(x, y) ? block(x, y, false) : null;
            return b != null && b.landing[at(x, y)];
        }

        /** Whether a square is left without a floor (see {@link #open}). */
        boolean isOpen(int x, int y) {
            Block b = inside(x, y) ? block(x, y, false) : null;
            return b != null && b.open[at(x, y)];
        }

        /** What stands on a square, or null. */
        String standing(int x, int y) {
            Block b = inside(x, y) ? block(x, y, false) : null;
            int t = b == null ? 0 : b.standing[at(x, y)];
            return t == 0 ? null : tiles.get(t);
        }
    }

    /** What a wall object is, once worked out: its style and whether it has a window. */
    private record WallKind(Style style, boolean window) {
    }

    /** A wall on the map: its object, level, tile, shape (0 straight, 2 corner) and turn. */
    private record Wall(int id, int level, int x, int y, int shape, int turn, WallKind kind) {
        /** The tile's sides it stands along: one for a straight wall, two for a corner. */
        int[] sides() {
            return shape == 2 ? new int[]{turn, (turn + 1) & 3} : new int[]{turn};
        }
    }

    /** A door's or gate's edge, where it closes: its level, tile and side (0 west, 1 north), the side's tile normalised as {@link #edgeKey} has it. */
    private record Door(int level, int x, int y, boolean west, boolean gate) {
    }

    private final Store store;
    private final Map<Integer, int[]> keys;
    private final ObjectManager objects;
    private final ModelExporter models;
    private final Map<Integer, WallKind> wallKinds = new HashMap<>();
    private final Map<String, Integer> counts = new TreeMap<>();
    private final GielinorFurniture furniture;

    GielinorObjects(Store store, Path keysFile, TileDefinitions tiles) throws IOException {
        this.store = store;
        this.furniture = new GielinorFurniture(tiles);
        this.keys = readKeys(keysFile);
        this.objects = new ObjectManager(store);
        objects.load();
        this.models = new ModelExporter(store);
    }

    GielinorFurniture furniture() {
        return furniture;
    }

    /** The map keys (OpenRS2's keys.json): each map square's four-int key, by square id. */
    private static Map<Integer, int[]> readKeys(Path file) throws IOException {
        Map<Integer, int[]> out = new HashMap<>();
        Matcher m = Pattern.compile("\"mapsquare\":\\s*(\\d+),\\s*\"key\":\\s*\\[\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+)")
            .matcher(Files.readString(file));
        while (m.find()) {
            out.put(Integer.parseInt(m.group(1)), new int[]{Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5))});
        }
        return out;
    }

    /**
     * Places the world's objects onto `levels`, each on the level it stands
     * on (see {@link GielinorTerrain#level}), leaving water bare of scenery.
     */
    void place(Placed[] levels, GielinorMap.GroundGrid ground, GielinorTerrain terrain) throws IOException {
        RegionLoader regions = new RegionLoader(store, keys::get);
        List<Wall> walls = new ArrayList<>();
        List<int[]> doorObjects = new ArrayList<>();
        List<int[]> stairs = new ArrayList<>();
        List<GielinorFurniture.Furnishing> furnishings = new ArrayList<>();
        for (int sx = GielinorMap.FIRST_SQUARE_X; sx <= GielinorMap.LAST_SQUARE_X; sx++) {
            for (int sy = GielinorMap.FIRST_SQUARE_Y; sy <= GielinorMap.LAST_SQUARE_Y; sy++) {
                if (!keys.containsKey(sx << 8 | sy)) {
                    continue;
                }
                LocationsDefinition locations = regions.loadLocDef(sx << 8 | sy);
                MapDefinition square = terrain.square(sx, sy);
                if (locations == null || square == null) {
                    continue;
                }
                for (Location l : locations.getLocations()) {
                    int lx = l.getPosition().getX();
                    int ly = l.getPosition().getY();
                    int level = GielinorTerrain.level(square, l.getPosition().getZ(), lx, ly);
                    if (level < 0 || level >= LEVELS) {
                        continue;
                    }
                    int worldX = sx * 64 + lx;
                    int worldY = sy * 64 + ly;
                    ObjectDefinition def = objects.getObject(l.getId());
                    if (def == null) {
                        continue;
                    }
                    int type = l.getType();
                    int turn = l.getOrientation() & 3;
                    int width = turn % 2 == 0 ? def.getSizeX() : def.getSizeY();
                    int height = turn % 2 == 0 ? def.getSizeY() : def.getSizeX();
                    GielinorFurniture.Kind furnishing = type == 10 || type == 11 ? furniture.kind(name(def), width, height) : null;
                    if (type == 0 || type == 2 || type == 9) {
                        if (isDoor(def)) {
                            if (type == 0) {
                                doorObjects.add(new int[]{level, worldX, worldY, turn, l.getId()});
                            }
                            continue;
                        }
                        WallKind kind = wallKind(def);
                        if (kind != null) {
                            walls.add(type == 9 ? squared(ground, l.getId(), level, worldX, worldY, turn, kind)
                                : new Wall(l.getId(), level, worldX, worldY, type, turn, kind));
                        }
                    } else if (type >= 12 && type <= 17) {
                        // A roof covers the tile on the level below it (its eaves,
                        // types 18 to 21, overhang the tiles outside the walls,
                        // which Zomboid's roofs don't).
                        ground.roof(level, worldX - GielinorMap.FIRST_SQUARE_X * 64, worldY - GielinorMap.FIRST_SQUARE_Y * 64,
                            roofMaterial(def, type));
                        counts.merge("roof tiles", 1, Integer::sum);
                    } else if ((type == 10 || type == 11) && isStaircase(def)) {
                        stairs.add(new int[]{level, worldX, worldY, width, height});
                    } else if (furnishing != null) {
                        furnishings.add(new GielinorFurniture.Furnishing(level, worldX, worldY, width, height, turn, furnishing));
                    } else if ((type == 10 || type == 11) && level == 0) {
                        scenery(levels[0], ground, def, l, worldX, worldY);
                    }
                }
            }
        }
        // Where each edge's wall is, for the doors to find their gaps.
        Map<Long, Style> edges = new HashMap<>();
        for (Wall w : walls) {
            for (int side : w.sides()) {
                edges.put(edgeKey(w.level, w.x, w.y, side), w.kind.style);
            }
        }
        // Nameless doors left standing open are doors, not walls: a solid
        // piece of a building's wall that closes into a doorway with wall
        // either side of it, or wall on one side and the other leaf of a
        // double door on the other.
        Map<Long, Door> shutAt = new HashMap<>();
        Map<Long, Wall> shutBy = new HashMap<>();
        for (Wall w : walls) {
            boolean leaf = w.shape == 0 && w.kind.style.building() && !w.kind.window && nameless(objects.getObject(w.id));
            Door d = leaf ? shutPlace(edges, w) : null;
            if (d != null) {
                shutAt.put(edgeKey(d), d);
                shutBy.put(edgeKey(d), w);
            }
        }
        List<Wall> opened = new ArrayList<>();
        for (Map.Entry<Long, Door> e : shutAt.entrySet()) {
            Door d = e.getValue();
            boolean before = edges.containsKey(along(d, -1)) || shutAt.containsKey(along(d, -1));
            boolean after = edges.containsKey(along(d, 1)) || shutAt.containsKey(along(d, 1));
            if (before && after) {
                opened.add(shutBy.get(e.getKey()));
            }
        }
        for (Wall w : opened) {
            edges.remove(edgeKey(w.level, w.x, w.y, w.turn));
            doorObjects.add(new int[]{w.level, w.x, w.y, w.turn, w.id});
        }
        counts.merge("doors standing open", opened.size(), Integer::sum);
        java.util.Set<Wall> doors = new java.util.HashSet<>(opened);
        for (Wall w : walls) {
            if (doors.contains(w)) {
                continue;
            }
            counts.merge(w.kind.window ? "windows" : w.kind.style.name().toLowerCase(Locale.ROOT), 1, Integer::sum);
            for (int side : w.sides()) {
                side(levels[w.level], w.x, w.y, side, w.kind.style, w.kind.window ? WINDOW : NONE);
            }
        }
        placeDoors(levels, doorObjects, edges);
        placeStairs(levels, stairs);
        furniture.place(levels, ground, furnishings, counts);
        System.out.println("  objects: " + counts);
    }

    private final Map<Integer, Integer> roofMaterials = new HashMap<>();

    /** A roof's style, by its model's colour (see {@link GielinorRoofs}). */
    private int roofMaterial(ObjectDefinition def, int shape) throws IOException {
        Integer known = roofMaterials.get(def.getId());
        if (known != null) {
            return known;
        }
        Mesh mesh = models.objectMesh(def, shape);
        double[] rgb = new double[3];
        double total = 0;
        for (int i = 0; i < mesh.faces.size(); i++) {
            int c = mesh.colours.get(i);
            rgb[0] += c >> 16 & 255;
            rgb[1] += c >> 8 & 255;
            rgb[2] += c & 255;
            total++;
        }
        for (int k = 0; k < 3; k++) {
            rgb[k] /= Math.max(total, 1);
        }
        int material = switch (colourName(rgb)) {
            case "red" -> GielinorRoofs.CLAY;
            case "brown", "tan" -> GielinorRoofs.BOARDS;
            case "beige" -> GielinorRoofs.PALE;
            case "green" -> GielinorRoofs.GREEN;
            default -> GielinorRoofs.SLATE;
        };
        roofMaterials.put(def.getId(), material);
        return material;
    }

    /** Whether an object is a staircase going up: named stairs, and climbed up. */
    private static boolean isStaircase(ObjectDefinition def) {
        return name(def).contains("stair") && hasOp(def, "climb-up");
    }

    /** Zomboid's wooden stairs: bottom, middle and top, going up westward and northward. */
    private static final String[] STAIRS_WEST = {"carpentry_02_88", "carpentry_02_89", "carpentry_02_90"};
    private static final String[] STAIRS_NORTH = {"carpentry_02_96", "carpentry_02_97", "carpentry_02_98"};

    /**
     * Places the staircases as Zomboid's stairs: a run of three squares (its
     * bottom, middle and top) up to the level above, westward along a
     * staircase wider than it's deep, else northward, at the far end of it so
     * the run's top comes out still within it: up to two runs side by side,
     * in one half of it on even levels and the other on odd ones (see
     * below). The squares above the stairs are left without a floor, as
     * Zomboid's own buildings have them. A staircase too small
     * for a run (a tile across, as the game's spiral stairs in narrow towers
     * can be) is left out.
     */
    private void placeStairs(Placed[] levels, List<int[]> stairs) {
        int scale = GielinorMap.SCALE;
        for (int[] st : stairs) {
            int level = st[0];
            if (level + 1 >= LEVELS) {
                continue;
            }
            Placed here = levels[level];
            Placed above = levels[level + 1];
            int width = st[3] * scale;
            int depth = st[4] * scale;
            int x0 = (st[1] - GielinorMap.FIRST_SQUARE_X * 64) * scale;
            // The footprint's northern row (the game's tiles run north as y grows).
            int y0 = here.height - (st[2] + st[4] - GielinorMap.FIRST_SQUARE_Y * 64) * scale;
            boolean west = width >= depth;
            int length = west ? width : depth;
            int across = west ? depth : width;
            if (length < 4) {
                counts.merge("stairs too small", 1, Integer::sum);
                continue;
            }
            counts.merge("stairs", 1, Integer::sum);
            // Stairs over stairs take the other half of the stairwell, by the
            // level's evenness, so a floor's stairs up don't stand in the hole
            // the stairs from below come up through.
            int half = across / 2;
            int first = level % 2 == 0 ? Math.max(0, half - 2) : half;
            int last = level % 2 == 0 ? half - 1 : Math.min(across - 1, half + 1);
            for (int lane = first; lane <= last; lane++) {
                for (int k = 0; k < 3; k++) {
                    // Bottom at the footprint's far (east or south) end, top two squares in.
                    int along = length - 1 - k;
                    int x = west ? x0 + along : x0 + lane;
                    int y = west ? y0 + lane : y0 + along;
                    here.stand(x, y, (west ? STAIRS_WEST : STAIRS_NORTH)[k]);
                    above.open(x, y);
                }
                // Where the run's top comes out, a floor (the game's can stop short under its spiral stairs).
                int out = length - 4;
                above.land(west ? x0 + out : x0 + lane, west ? y0 + lane : y0 + out);
            }
        }
    }

    /** Whether an object is a door or a gate: it opens or closes, or says it's one. */
    private static boolean isDoor(ObjectDefinition def) {
        String name = name(def);
        return opens(def) || closes(def) || name.contains("door") || name.contains("gate");
    }

    private static boolean nameless(ObjectDefinition def) {
        String name = name(def);
        return name.isEmpty() || name.equals("null");
    }

    /**
     * Where a nameless piece of wall would close if it were a door left
     * standing open: nothing continues its line either way, and swung shut
     * (see {@link #swungShut}) it lands in a gap in a wall, with wall on at
     * least one side. Null if it isn't one.
     */
    private Door shutPlace(Map<Long, Style> edges, Wall w) {
        Door here = door(w.level, w.x, w.y, w.turn, false);
        if (edges.containsKey(along(here, 1)) || edges.containsKey(along(here, -1))) {
            return null;
        }
        for (int[] c : swungShut(objects.getObject(w.id), w.x, w.y, w.turn)) {
            Door shut = door(w.level, c[0], c[1], c[2], false);
            if (!edges.containsKey(edgeKey(shut)) && (edges.containsKey(along(shut, 1)) || edges.containsKey(along(shut, -1)))) {
                return shut;
            }
        }
        return null;
    }

    /**
     * Where a door stored open closes: its tile and side, as the game turns
     * it back, a single door one way and a leaf of a double door the other
     * (which is which isn't stored, so both).
     */
    private static List<int[]> swungShut(ObjectDefinition def, int x, int y, int turn) {
        boolean flip = def.isRotated();
        int[] singleX = {0, 1, 0, -1};
        int[] singleY = {1, 0, -1, 0};
        int[] pairX = flip ? new int[]{0, 1, 0, -1} : new int[]{0, -1, 0, 1};
        int[] pairY = flip ? new int[]{-1, 0, 1, 0} : new int[]{1, 0, -1, 0};
        return List.of(new int[]{x + singleX[turn], y + singleY[turn], (turn + (flip ? 1 : 3)) & 3},
            new int[]{x + pairX[turn], y + pairY[turn], (turn + (flip ? 3 : 1)) & 3});
    }

    private static String name(ObjectDefinition def) {
        return def.getName() == null ? "" : def.getName().toLowerCase(Locale.ROOT);
    }

    private static boolean opens(ObjectDefinition def) {
        return hasOp(def, "open");
    }

    private static boolean closes(ObjectDefinition def) {
        return hasOp(def, "close");
    }

    private static boolean hasOp(ObjectDefinition def, String op) {
        EntityOpsDefinition ops = def.getOps();
        if (ops == null || ops.getOps() == null) {
            return false;
        }
        for (EntityOpsDefinition.Op o : ops.getOps()) {
            if (o != null && o.text != null && o.text.toLowerCase(Locale.ROOT).equals(op)) {
                return true;
            }
        }
        return false;
    }

    /**
     * An edge's key: its level, and the tile and side (west or north) it's
     * on, an east side taken as the west side of the tile east of it and a
     * south side as the north side of the tile south of it.
     */
    private static long edgeKey(int level, int x, int y, int side) {
        int ex = side == 2 ? x + 1 : x;
        int ey = side == 3 ? y - 1 : y;
        boolean west = side == 0 || side == 2;
        return ((long) level << 40) | ((long) ex << 22) | ((long) ey << 2) | (west ? 0 : 1);
    }

    private static long edgeKey(Door d) {
        return edgeKey(d.level, d.x, d.y, d.west ? 0 : 1);
    }

    /** The edge along the line from `d` (`step` tiles on, either way). */
    private static long along(Door d, int step) {
        return edgeKey(d.level, d.west ? d.x : d.x + step, d.west ? d.y + step : d.y, d.west ? 0 : 1);
    }

    /**
     * Places the doors and gates: each in its doorway (see the class
     * comment), side by side ones as double doors, a door in a building's
     * wall as a door, anything else as a gate.
     */
    private void placeDoors(Placed[] levels, List<int[]> doorObjects, Map<Long, Style> edges) {
        Map<Long, Door> doors = new java.util.LinkedHashMap<>();
        for (int[] o : doorObjects) {
            ObjectDefinition def = objects.getObject(o[4]);
            int level = o[0];
            int x = o[1];
            int y = o[2];
            int turn = o[3];
            // Stored open (it closes but doesn't open, or it's a nameless
            // door left open): back into the doorway it swung out of.
            boolean open = closes(def) && !opens(def) || nameless(def) && !closes(def) && !opens(def);
            List<int[]> candidates = open ? swungShut(def, x, y, turn) : List.of(new int[]{x, y, turn});
            int[] best = null;
            int bestScore = Integer.MIN_VALUE;
            for (int[] c : candidates) {
                long key = edgeKey(level, c[0], c[1], c[2]);
                Door probe = door(level, c[0], c[1], c[2], false);
                int score = (edges.containsKey(key) ? -4 : 0) + (edges.containsKey(along(probe, 1)) ? 1 : 0)
                    + (edges.containsKey(along(probe, -1)) ? 1 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
            Door d = door(level, best[0], best[1], best[2], name(def).contains("gate"));
            doors.putIfAbsent(edgeKey(d), d);
        }
        java.util.Set<Long> done = new java.util.HashSet<>();
        for (Door d : doors.values()) {
            long key = edgeKey(d);
            if (done.contains(key)) {
                continue;
            }
            done.add(key);
            // Its partner, if it's half of a double door: the next tile along the line.
            Door partner = null;
            for (int step : new int[]{1, -1}) {
                Door next = doors.get(along(d, step));
                if (partner == null && next != null && !done.contains(edgeKey(next))) {
                    partner = next;
                }
            }
            Style frame = frame(edges, d, partner);
            boolean gate = d.gate || !frame.building();
            Placed placed = levels[d.level];
            if (partner != null) {
                done.add(edgeKey(partner));
                Door first = d.west ? (d.y < partner.y ? d : partner) : (d.x < partner.x ? d : partner);
                counts.merge(gate ? "double gates" : "double doors", 1, Integer::sum);
                // Four squares along the edge: a double gate's pieces, or a wall, two doors and a wall.
                for (int i = 0; i < 4; i++) {
                    int[] sq = square(placed, first, i);
                    int gap = gate ? DOUBLE_GATE + i : i == 1 || i == 2 ? DOOR : NONE;
                    placed.edge(sq[0], sq[1], first.west, frame, gap);
                }
            } else {
                counts.merge(gate ? "gates" : "doors", 1, Integer::sum);
                for (int i = 0; i < 2; i++) {
                    int[] sq = square(placed, d, i);
                    placed.edge(sq[0], sq[1], d.west, frame, i == 0 ? (gate ? GATE : DOOR) : NONE);
                }
            }
        }
    }

    /** A door on side `side` (0 to 3) of a tile, normalised to a west or north side. */
    private static Door door(int level, int x, int y, int side, boolean gate) {
        return new Door(level, side == 2 ? x + 1 : x, side == 3 ? y - 1 : y, side == 0 || side == 2, gate);
    }

    /**
     * The `i`th Zomboid square along a door's edge (and the next tile's, for
     * a double door): for a west edge from south to north, as Zomboid counts
     * a west double gate's pieces; for a north edge from west to east.
     */
    private static int[] square(Placed placed, Door d, int i) {
        int scale = GielinorMap.SCALE;
        int zx = (d.x - GielinorMap.FIRST_SQUARE_X * 64) * scale;
        int zy = placed.height - (d.y - GielinorMap.FIRST_SQUARE_Y * 64 + 1) * scale;
        // A west edge's squares run south (larger y) to north; the tile's southern square is zy + 1.
        return d.west ? new int[]{zx, zy + scale - 1 - i} : new int[]{zx + i, zy};
    }

    /** The wall a door stands in: the nearest wall along its line, either way (a building's first), else wood. */
    private static Style frame(Map<Long, Style> edges, Door d, Door partner) {
        Style found = null;
        for (int step = 1; step <= 3 && found == null; step++) {
            for (int dir : new int[]{1, -1}) {
                Door from = partner != null && Integer.signum(partner.west ? partner.y - d.y : partner.x - d.x) == dir ? partner : d;
                Style s = edges.get(along(from, dir * step));
                if (s != null && (found == null || s.building() && !found.building())) {
                    found = s;
                }
            }
        }
        return found != null ? found : Style.WOOD;
    }

    /**
     * A straight wall along one side of a tile: the game's sides are 0 west,
     * 1 north, 2 east, 3 south, and a tile is two squares a side.
     */
    private static void side(Placed placed, int worldX, int worldY, int side, Style style, int gap) {
        int scale = GielinorMap.SCALE;
        int x = (worldX - GielinorMap.FIRST_SQUARE_X * 64) * scale;
        int y = placed.height - (worldY - GielinorMap.FIRST_SQUARE_Y * 64 + 1) * scale;
        for (int i = 0; i < scale; i++) {
            switch (side) {
                case 0 -> placed.edge(x, y + i, true, style, gap);
                case 1 -> placed.edge(x + i, y, false, style, gap);
                case 2 -> placed.edge(x + scale, y + i, true, style, gap);
                default -> placed.edge(x + i, y + scale, false, style, gap);
            }
        }
    }

    /**
     * A wall across a tile, from corner to corner, as the corner of two of
     * the tile's sides: Zomboid has no slanted walls. Turned 0 or 2 it runs
     * from the tile's south-west corner to its north-east, so it's either
     * the tile's west and north sides or its east and south; else from its
     * north-west corner to its south-east, so its north and east sides or
     * its south and west. The pair facing out of the building wins (the
     * game cuts a building's corners so, and its floors and roofs above
     * still cover the whole tile), and the tile is then inside; along a
     * wall standing in the open (a castle's angled walls) the tiles step
     * along it a tile at a time.
     */
    private static Wall squared(GielinorMap.GroundGrid ground, int id, int level, int x, int y, int turn, WallKind kind) {
        boolean rising = turn % 2 == 0;
        // Corner turns: 0 west and north, 1 north and east, 2 east and south, 3 south and west.
        int first = rising ? 0 : 1;
        int second = rising ? 2 : 3;
        int tx = x - GielinorMap.FIRST_SQUARE_X * 64;
        int ty = y - GielinorMap.FIRST_SQUARE_Y * 64;
        int firstIn = indoorBeyond(ground, level, tx, ty, first);
        int secondIn = indoorBeyond(ground, level, tx, ty, second);
        int corner = firstIn <= secondIn ? first : second;
        if (firstIn != secondIn) {
            ground.setIndoorTile(level, tx, ty);
        }
        return new Wall(id, level, x, y, 2, corner, kind);
    }

    /** How many of the tiles beyond a corner's two sides (see {@link Wall#sides}) are inside a building. */
    private static int indoorBeyond(GielinorMap.GroundGrid ground, int level, int tx, int ty, int corner) {
        int count = 0;
        for (int side : new int[]{corner, (corner + 1) & 3}) {
            int nx = side == 0 ? tx - 1 : side == 2 ? tx + 1 : tx;
            int ny = side == 1 ? ty + 1 : side == 3 ? ty - 1 : ty;
            count += ground.indoorTile(level, nx, ny) ? 1 : 0;
        }
        return count;
    }

    /** A tree, bush, plant or rock on the ground, one for each of the game's tiles it covers, somewhere on that tile. */
    private void scenery(Placed placed, GielinorMap.GroundGrid ground, ObjectDefinition def, Location l, int worldX, int worldY) {
        if (rock(placed, ground, def, l, worldX, worldY)) {
            return;
        }
        String[] choices = standingChoices(def);
        if (choices == null) {
            return;
        }
        counts.merge(choices[0], 1, Integer::sum);
        int scale = GielinorMap.SCALE;
        int x = (worldX - GielinorMap.FIRST_SQUARE_X * 64) * scale;
        int y = placed.height - (worldY - GielinorMap.FIRST_SQUARE_Y * 64 + 1) * scale;
        int turn = l.getOrientation() & 3;
        int w = turn % 2 == 0 ? def.getSizeX() : def.getSizeY();
        int h = turn % 2 == 0 ? def.getSizeY() : def.getSizeX();
        for (int tx = 0; tx < w; tx++) {
            for (int ty = 0; ty < h; ty++) {
                int zx = x + tx * scale;
                int zy = y - ty * scale;
                int hash = GielinorMap.scramble(zx, zy);
                int sx = zx + Math.floorMod(hash, scale);
                int sy = zy + Math.floorMod(hash >> 8, scale);
                if (!placed.inside(sx, sy) || ground.at(sx, sy) == null || ground.at(sx, sy) == GielinorTerrain.Ground.WATER) {
                    continue;
                }
                placed.stand(sx, sy, choices[1 + Math.floorMod(hash >> 16, choices.length - 1)]);
            }
        }
    }

    /**
     * Zomboid's large boulders: four, each four sprites making one rock across
     * two squares by two, by their places in it (SpriteGridPos 0,0 1,0 0,1 1,1).
     */
    private static final int[][] BOULDERS_2X2 = {{19, 18, 16, 17}, {23, 22, 20, 21}, {27, 26, 24, 25}, {31, 30, 28, 29}};
    /** Zomboid's ore rocks (crafting_ore), large then medium, for each ore it has. */
    private static final int[][] IRON_ORE = {{6, 7, 8}, {3, 4, 5}};
    private static final int[][] COPPER_ORE = {{22, 23, 24}, {19, 20, 21}};
    private static final int[][] FLINT = {{32, 33, 34, 35}, {36, 37, 38, 39}};
    private static final int[][] LIMESTONE = {{40, 41, 42, 43}, {44, 45, 46, 47}};

    /**
     * Places a rock, if the object is one: a mining rock as the Zomboid ore
     * nearest its ore (B42 mines iron and copper ore, flint and limestone),
     * a large ore rock and a medium one on each of the game's tiles it
     * covers; any other solid rock as a whole large boulder filling each
     * tile. Returns whether it was a rock (small loose stones aren't: they're
     * left to {@link #standingChoices}).
     */
    private boolean rock(Placed placed, GielinorMap.GroundGrid ground, ObjectDefinition def, Location l, int worldX, int worldY) {
        String name = name(def);
        boolean mined = hasOp(def, "mine");
        boolean solidRock = def.getInteractType() != 0 && (name.contains("rock") || name.contains("boulder") || name.equals("stone"));
        if (!mined && !solidRock) {
            return false;
        }
        int[][] ore = !mined ? null
            : name.contains("copper") || name.contains("tin") ? COPPER_ORE
            : name.contains("limestone") || name.contains("clay") || name.contains("sandstone") ? LIMESTONE
            : name.contains("gem") || name.contains("granite") ? FLINT
            : name.equals("rocks") || name.contains("pile") || name.contains("slide") ? null
            : IRON_ORE;
        counts.merge(ore == null ? "boulders" : ore == COPPER_ORE ? "copper ore" : ore == IRON_ORE ? "iron ore" : ore == FLINT ? "flint" : "limestone",
            1, Integer::sum);
        int scale = GielinorMap.SCALE;
        int x0 = (worldX - GielinorMap.FIRST_SQUARE_X * 64) * scale;
        int y0 = placed.height - (worldY - GielinorMap.FIRST_SQUARE_Y * 64 + 1) * scale;
        int turn = l.getOrientation() & 3;
        int w = turn % 2 == 0 ? def.getSizeX() : def.getSizeY();
        int h = turn % 2 == 0 ? def.getSizeY() : def.getSizeX();
        for (int tx = 0; tx < w; tx++) {
            for (int ty = 0; ty < h; ty++) {
                int x = x0 + tx * scale;
                int y = y0 - ty * scale;
                if (!dry(placed, ground, x, y) || !dry(placed, ground, x + 1, y + 1)) {
                    continue;
                }
                int hash = GielinorMap.scramble(x, y);
                if (ore == null) {
                    int[] group = BOULDERS_2X2[Math.floorMod(hash, BOULDERS_2X2.length)];
                    placed.stand(x, y, "boulders_" + group[0]);
                    placed.stand(x + 1, y, "boulders_" + group[1]);
                    placed.stand(x, y + 1, "boulders_" + group[2]);
                    placed.stand(x + 1, y + 1, "boulders_" + group[3]);
                } else {
                    // A large rock in one corner and a medium one across from it.
                    boolean flip = (hash & 1) == 0;
                    placed.stand(flip ? x : x + 1, y, "crafting_ore_" + ore[0][Math.floorMod(hash >> 4, ore[0].length)]);
                    placed.stand(flip ? x + 1 : x, y + 1, "crafting_ore_" + ore[1][Math.floorMod(hash >> 8, ore[1].length)]);
                }
            }
        }
        return true;
    }

    private static boolean dry(Placed placed, GielinorMap.GroundGrid ground, int x, int y) {
        return placed.inside(x, y) && ground.at(x, y) != null && ground.at(x, y) != GielinorTerrain.Ground.WATER;
    }

    /**
     * What a scenery object becomes, by its name: a kind (for the counts)
     * then the tiles to choose from, or null for nothing.
     */
    private static String[] standingChoices(ObjectDefinition def) {
        String name = name(def);
        if (name.isEmpty() || name.equals("null")) {
            return null;
        }
        if ((name.contains("tree") || name.equals("evergreen") || name.equals("oak") || name.equals("willow") || name.equals("maple")
                || name.equals("yew") || name.equals("teak") || name.equals("mahogany") || name.contains("palm"))
                && !name.contains("stump") && !name.contains("patch") && !name.contains("log")) {
            return tiles("trees", "vegetation_trees_01", TREES);
        }
        if (name.contains("rock") || name.contains("boulder") || name.equals("stone") || name.equals("stones")) {
            return tiles("stones", "boulders", STONES);
        }
        if (name.contains("bush") || name.contains("hedge") || name.contains("bramble") || name.equals("plant") || name.equals("plants")
                || name.contains("jungle plant") || name.contains("creeping plant") || name.contains("cactus") || name.contains("shrub")) {
            return tiles("bushes", "vegetation_foliage_01", BUSHES);
        }
        if (name.contains("fern") || name.contains("flower") || name.contains("daisies") || name.contains("heather")
                || name.contains("nettle") || name.contains("bullrush") || name.contains("reed") || name.contains("grass")
                || name.contains("mushroom") || name.contains("fungus") || name.contains("weed")) {
            return tiles("plants", "vegetation_groundcover_01", GROUND_PLANTS);
        }
        return null;
    }

    private static String[] tiles(String kind, String sheet, int[] indices) {
        String[] out = new String[indices.length + 1];
        out[0] = kind;
        for (int i = 0; i < indices.length; i++) {
            out[i + 1] = sheet + "_" + indices[i];
        }
        return out;
    }

    /** What a wall object is (see the class comment), or null to leave it out. */
    private WallKind wallKind(ObjectDefinition def) throws IOException {
        if (wallKinds.containsKey(def.getId())) {
            return wallKinds.get(def.getId());
        }
        WallKind kind = sortWall(def);
        wallKinds.put(def.getId(), kind);
        return kind;
    }

    private WallKind sortWall(ObjectDefinition def) throws IOException {
        if (def.getInteractType() == 0) {
            return null;
        }
        Mesh mesh = models.objectMesh(def, 0);
        if (mesh.faces.isEmpty()) {
            return null;
        }
        double top = 0;
        for (double[] v : mesh.vertices) {
            top = Math.max(top, -v[1]);
        }
        if (top < 8) {
            return null;
        }
        String colour = colourName(averageColour(mesh));
        double solid = covered(mesh, top, 0.05, 0.95);
        if (top >= LOW && (name(def).contains("window") || window(mesh, top) && (def.isBlocksProjectile() || solid >= SOLID))) {
            // A building's wall with a window: big windows leave it more gap
            // than wall, and arrows fly through some.
            return new WallKind(buildingStyle(colour), true);
        }
        double[] rgb = averageColour(mesh);
        double chroma = (Math.max(rgb[0], Math.max(rgb[1], rgb[2])) - Math.min(rgb[0], Math.min(rgb[1], rgb[2]))) / 255;
        // The game's wood is warm and strongly coloured; its stone and iron are near grey.
        boolean wooden = chroma >= 0.2 && rgb[0] > rgb[2];
        if (!def.isBlocksProjectile() || solid < SOLID) {
            // Arrows fly over it, or it's mostly gaps: a fence, a hedge, a
            // dry stone wall or an iron railing.
            if (wooden || colour.equals("green")) {
                return new WallKind(Style.FENCE, false);
            }
            return new WallKind(solid >= STONE_SOLID ? Style.LOW_WALL : Style.RAILING, false);
        }
        if (top < LOW) {
            return new WallKind(Style.LOW_WALL, false);
        }
        return new WallKind(buildingStyle(colour), window(mesh, top));
    }

    /** How much of a wall's face must be covered for it to be a wall rather than a fence. */
    private static final double SOLID = 0.6;
    /** A stone wall that arrows fly over (a dry stone wall's uneven top) is at least this solid; an iron railing less. */
    private static final double STONE_SOLID = 0.4;
    /** A wall lower than this (in the game's units, a tile being 128 across) is a low wall. */
    private static final double LOW = 120;

    private static Style buildingStyle(String colour) {
        return switch (colour) {
            case "white", "beige" -> Style.PLASTER;
            case "red" -> Style.BRICK;
            case "brown", "green" -> Style.WOOD;
            case "tan" -> Style.STONE;
            default -> Style.GREY_STONE;
        };
    }

    /** The average colour of a wall's faces that face sideways (its sides, not its top), weighted by their area. */
    private static double[] averageColour(Mesh mesh) {
        double[] rgb = new double[3];
        double total = 0;
        for (int i = 0; i < mesh.faces.size(); i++) {
            if (Math.abs(mesh.normal(i)[1]) > 0.7) {
                continue;
            }
            int[] f = mesh.faces.get(i);
            double[] n = ModelExporter.cross(ModelExporter.sub(mesh.vertices.get(f[1]), mesh.vertices.get(f[0])),
                ModelExporter.sub(mesh.vertices.get(f[2]), mesh.vertices.get(f[0])));
            double area = Math.sqrt(ModelExporter.dot(n, n));
            int c = mesh.colours.get(i);
            rgb[0] += (c >> 16 & 255) * area;
            rgb[1] += (c >> 8 & 255) * area;
            rgb[2] += (c & 255) * area;
            total += area;
        }
        for (int k = 0; k < 3; k++) {
            rgb[k] /= Math.max(total, 1e-9);
        }
        return rgb;
    }

    /** A colour's rough name: grey, white, beige, tan, brown, red, green or blue. */
    static String colourName(double[] rgb) {
        double r = rgb[0] / 255;
        double g = rgb[1] / 255;
        double b = rgb[2] / 255;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double light = (max + min) / 2;
        double chroma = max - min;
        if (chroma < 0.08) {
            return light > 0.6 ? "white" : "grey";
        }
        if (r > g && g >= b) {
            return light > 0.55 ? "beige" : r - b > 0.25 ? "brown" : "tan";
        }
        if (r > g && r > b) {
            return "red";
        }
        return g >= r && g >= b ? "green" : "blue";
    }

    /**
     * Whether a wall's face has a window: an opening halfway up, across the
     * middle of the wall, with wall below and above it.
     */
    private static boolean window(Mesh mesh, double top) {
        return covered(mesh, top, 0.4, 0.6) < 0.7 && covered(mesh, top, 0.03, 0.15) > 0.85 && covered(mesh, top, 0.88, 0.97) > 0.6;
    }

    /**
     * How much of a band of a straight wall's face (from `from` to `to` of
     * its height) its faces cover. The game's straight walls stand along the
     * west side of their tile, so their face lies across z (from -64 to 64)
     * and y.
     */
    private static double covered(Mesh mesh, double top, double from, double to) {
        int hit = 0;
        int all = 0;
        for (double z = -56; z <= 56; z += 8) {
            for (double t = from; t <= to + 1e-9; t += (to - from) / 6) {
                all++;
                if (covers(mesh, z, -top * t)) {
                    hit++;
                }
            }
        }
        return (double) hit / all;
    }

    private static boolean covers(Mesh mesh, double z, double y) {
        for (int[] f : mesh.faces) {
            double[] a = mesh.vertices.get(f[0]);
            double[] b = mesh.vertices.get(f[1]);
            double[] c = mesh.vertices.get(f[2]);
            double d1 = (z - b[2]) * (a[1] - b[1]) - (a[2] - b[2]) * (y - b[1]);
            double d2 = (z - c[2]) * (b[1] - c[1]) - (b[2] - c[2]) * (y - c[1]);
            double d3 = (z - a[2]) * (c[1] - a[1]) - (c[2] - a[2]) * (y - a[1]);
            boolean negative = d1 < 0 || d2 < 0 || d3 < 0;
            boolean positive = d1 > 0 || d2 > 0 || d3 > 0;
            if (!(negative && positive)) {
                return true;
            }
        }
        return false;
    }
}
