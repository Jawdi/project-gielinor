package gielinor.builder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.definitions.LocationsDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.RegionLoader;

/**
 * The things standing in the game's world, on its ground level: walls,
 * fences, doors, gates and windows along the edges of its tiles, and trees,
 * bushes, plants and rocks on them, each as Zomboid's own (so its doors
 * open, its trees are chopped and grow through the seasons, its fences are
 * climbed). Read from the game's map squares (each square's objects are
 * locked with a key, see {@link Pinned#keys}).
 *
 * The game's walls are mostly unnamed, so a wall is sorted by its model:
 * walls that don't block are left out (archways), as are walls with nothing
 * to see (the invisible lines the game fences cliffs and edges with, which
 * Zomboid has no use for); low walls are fences, railings or low walls by
 * colour and whether they can be seen through; the rest are buildings' walls
 * of stone, plaster, brick or logs by colour, with a window where the model
 * has an opening halfway up.
 */
final class GielinorObjects {
    /** A wall's look: which of Zomboid's wall tiles it's made of. */
    enum Style {
        STONE("walls_exterior_house_02", 0),
        GREY_STONE("walls_exterior_house_02", 16),
        PLASTER("walls_exterior_house_02", 48),
        BRICK("walls_exterior_house_02", 64),
        LOGS("walls_logs", 0),
        LOW_WALL("walls_exterior_house_low_01", 0),
        FENCE("fencing_01", -1),
        RAILING("fencing_01", -2);

        final String sheet;
        final int base;

        Style(String sheet, int base) {
            this.sheet = sheet;
            this.base = base;
        }

        /** Whether it's a building's wall, with windows and door frames. */
        boolean building() {
            return base >= 0 && this != LOW_WALL;
        }

        /** The west wall, north wall or both (a corner), as a tile. */
        String wall(boolean west, boolean north) {
            if (this == FENCE) {
                return "fencing_01_" + (west && north ? 36 : west ? 34 : 32);
            }
            if (this == RAILING) {
                return "fencing_01_" + (west && north ? 3 : west ? 2 : 1);
            }
            return sheet + "_" + (base + (west && north ? 2 : west ? 0 : 1));
        }

        /** A window's or door's frame in the wall, as a tile (buildings only). */
        String frame(boolean west, boolean door) {
            return sheet + "_" + (base + (door ? 10 : 8) + (west ? 0 : 1));
        }
    }

    /** What's in an edge's gap. */
    static final int NONE = 0;
    static final int DOOR = 1;
    static final int WINDOW = 2;
    static final int GATE = 3;

    /** Zomboid's tiles for the things standing on tiles. */
    private static final int[] TREES = {8, 9, 10, 11, 13, 14, 15, 17, 24, 25};
    private static final int[] BUSHES = {0, 1, 2, 3, 4, 5, 8, 9, 10, 11, 12, 13, 14};
    private static final int[] GROUND_PLANTS = {16, 17, 18, 19, 20, 21, 22, 23};
    private static final int[] BOULDERS = {16, 17, 18, 19, 20, 21, 22, 23};
    private static final int[] STONES = {8, 9, 10, 11, 12, 13, 14, 15};

    /**
     * Everything placed, by Zomboid square (as {@link GielinorMap.GroundGrid}
     * lays the world out): each square's west and north edge (a style's
     * ordinal plus one, and what's in its gap) and what stands on it (a
     * tile, by its place in {@link #tiles}).
     */
    static final class Placed {
        final int width;
        final int height;
        final byte[] westStyle;
        final byte[] northStyle;
        final byte[] westGap;
        final byte[] northGap;
        final short[] standing;
        final java.util.List<String> tiles = new java.util.ArrayList<>(java.util.List.of(""));
        private final Map<String, Short> tileIndex = new HashMap<>();

        Placed(int width, int height) {
            this.width = width;
            this.height = height;
            int n = width * height;
            westStyle = new byte[n];
            northStyle = new byte[n];
            westGap = new byte[n];
            northGap = new byte[n];
            standing = new short[n];
        }

        boolean inside(int x, int y) {
            return x >= 0 && y >= 0 && x < width && y < height;
        }

        void edge(int x, int y, boolean west, Style style, int gap) {
            if (!inside(x, y)) {
                return;
            }
            int i = x + y * width;
            (west ? westStyle : northStyle)[i] = (byte) (style == null ? 0 : style.ordinal() + 1);
            (west ? westGap : northGap)[i] = (byte) gap;
        }

        Style style(int x, int y, boolean west) {
            int s = (west ? westStyle : northStyle)[x + y * width];
            return s == 0 ? null : Style.values()[s - 1];
        }

        int gap(int x, int y, boolean west) {
            return (west ? westGap : northGap)[x + y * width];
        }

        void stand(int x, int y, String tile) {
            if (!inside(x, y)) {
                return;
            }
            short t = tileIndex.computeIfAbsent(tile, k -> {
                tiles.add(k);
                return (short) (tiles.size() - 1);
            });
            standing[x + y * width] = t;
        }

        /** What stands on a square, or null. */
        String standing(int x, int y) {
            int t = standing[x + y * width];
            return t == 0 ? null : tiles.get(t);
        }
    }

    /** What a wall object is, once worked out. */
    private record WallKind(Style style, int gap) {
    }

    private final Store store;
    private final Map<Integer, int[]> keys;
    private final ObjectManager objects;
    private final ModelExporter models;
    private final Map<Integer, WallKind> wallKinds = new HashMap<>();
    private final Map<String, Integer> counts = new TreeMap<>();

    GielinorObjects(Store store, Path keysFile) throws IOException {
        this.store = store;
        this.keys = readKeys(keysFile);
        this.objects = new ObjectManager(store);
        objects.load();
        this.models = new ModelExporter(store);
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

    /** Places the ground level's objects of the world's surface onto `placed`, leaving water bare. */
    void place(Placed placed, GielinorMap.GroundGrid ground) throws IOException {
        RegionLoader regions = new RegionLoader(store, keys::get);
        for (int sx = GielinorMap.FIRST_SQUARE_X; sx <= GielinorMap.LAST_SQUARE_X; sx++) {
            for (int sy = GielinorMap.FIRST_SQUARE_Y; sy <= GielinorMap.LAST_SQUARE_Y; sy++) {
                if (!keys.containsKey(sx << 8 | sy)) {
                    continue;
                }
                LocationsDefinition locations = regions.loadLocDef(sx << 8 | sy);
                if (locations == null) {
                    continue;
                }
                for (Location l : locations.getLocations()) {
                    if (l.getPosition().getZ() == 0) {
                        place(placed, ground, l, sx * 64 + l.getPosition().getX(), sy * 64 + l.getPosition().getY());
                    }
                }
            }
        }
        System.out.println("  objects: " + counts);
    }

    private void place(Placed placed, GielinorMap.GroundGrid ground, Location l, int worldX, int worldY) throws IOException {
        ObjectDefinition def = objects.getObject(l.getId());
        if (def == null) {
            return;
        }
        int scale = GielinorMap.SCALE;
        // The tile's top-left Zomboid square (the game's y runs north, Zomboid's south).
        int x = (worldX - GielinorMap.FIRST_SQUARE_X * 64) * scale;
        int y = placed.height - (worldY - GielinorMap.FIRST_SQUARE_Y * 64 + 1) * scale;
        int type = l.getType();
        int turn = l.getOrientation() & 3;
        if (type == 0 || type == 2 || type == 9) {
            WallKind kind = wallKind(def);
            if (kind == null) {
                return;
            }
            counts.merge(kind.gap == DOOR ? "doors" : kind.gap == GATE ? "gates" : kind.gap == WINDOW ? "windows"
                : kind.style.name().toLowerCase(Locale.ROOT), 1, Integer::sum);
            if (type == 0) {
                side(placed, x, y, turn, kind);
            } else if (type == 2) {
                side(placed, x, y, turn, kind);
                side(placed, x, y, (turn + 1) & 3, kind);
            } else {
                diagonal(placed, x, y, turn, kind);
            }
            return;
        }
        if (type != 10 && type != 11) {
            return;
        }
        String[] choices = standingChoices(def);
        if (choices == null) {
            return;
        }
        counts.merge(choices[0], 1, Integer::sum);
        // One for each of the game's tiles it covers, somewhere on that tile.
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
     * A straight wall along one side of the tile whose top-left square is
     * (x, y): the game's sides are 0 west, 1 north, 2 east, 3 south. A
     * tile is two squares a side; a door or gate takes the first of them,
     * the second its wall.
     */
    private static void side(Placed placed, int x, int y, int side, WallKind kind) {
        int scale = GielinorMap.SCALE;
        for (int i = 0; i < scale; i++) {
            int gap = kind.gap == WINDOW || i == 0 ? kind.gap : NONE;
            switch (side) {
                case 0 -> placed.edge(x, y + i, true, kind.style, gap);
                case 1 -> placed.edge(x + i, y, false, kind.style, gap);
                case 2 -> placed.edge(x + scale, y + i, true, kind.style, gap);
                default -> placed.edge(x + i, y + scale, false, kind.style, gap);
            }
        }
    }

    /**
     * A wall across the tile, from corner to corner: Zomboid has no slanted
     * walls, so it steps across the tile's squares. Turned 0 or 2 it runs
     * from the tile's south-west corner to its north-east, else from its
     * north-west to its south-east.
     */
    private static void diagonal(Placed placed, int x, int y, int turn, WallKind kind) {
        Style s = kind.style;
        if (turn % 2 == 0) {
            placed.edge(x, y + 1, true, s, NONE);
            placed.edge(x, y + 1, false, s, NONE);
            placed.edge(x + 1, y, true, s, NONE);
            placed.edge(x + 1, y, false, s, NONE);
        } else {
            placed.edge(x, y, false, s, NONE);
            placed.edge(x + 1, y, true, s, NONE);
            placed.edge(x + 1, y + 1, false, s, NONE);
            placed.edge(x + 2, y + 1, true, s, NONE);
        }
    }

    /**
     * What a scenery object becomes, by its name: a kind (for the counts)
     * then the tiles to choose from, or null for nothing.
     */
    private static String[] standingChoices(ObjectDefinition def) {
        String name = def.getName() == null ? "" : def.getName().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.equals("null")) {
            return null;
        }
        if ((name.contains("tree") || name.equals("evergreen") || name.equals("oak") || name.equals("willow") || name.equals("maple")
                || name.equals("yew") || name.equals("teak") || name.equals("mahogany") || name.contains("palm"))
                && !name.contains("stump") && !name.contains("patch") && !name.contains("log")) {
            return tiles("trees", "vegetation_trees_01", TREES);
        }
        if (name.contains("rock") || name.contains("boulder") || name.equals("stone") || name.equals("stones")) {
            return def.getInteractType() != 0 ? tiles("rocks", "boulders", BOULDERS) : tiles("stones", "boulders", STONES);
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
        String name = def.getName() == null ? "" : def.getName().toLowerCase(Locale.ROOT);
        boolean opens = false;
        if (def.getOps() != null) {
            String ops = String.valueOf(def.getOps()).toLowerCase(Locale.ROOT);
            opens = ops.contains("open");
        }
        Mesh mesh = wallMesh(def);
        if (mesh.faces.isEmpty() && !opens) {
            return null;
        }
        double top = 0;
        for (double[] v : mesh.vertices) {
            top = Math.max(top, -v[1]);
        }
        double[] rgb = averageColour(mesh);
        String colour = colourName(rgb);
        boolean low = top < 170;
        if (opens || name.contains("door") || name.contains("gate")) {
            boolean gate = name.contains("gate") && !name.contains("city") || low;
            return new WallKind(gate ? Style.FENCE : null, gate ? GATE : DOOR);
        }
        if (low) {
            boolean wooden = colour.equals("brown") || colour.equals("tan") || colour.equals("green");
            if (wooden) {
                return new WallKind(Style.FENCE, NONE);
            }
            return new WallKind(def.isBlocksProjectile() ? Style.LOW_WALL : Style.RAILING, NONE);
        }
        Style style = switch (colour) {
            case "white", "beige" -> Style.PLASTER;
            case "red" -> Style.BRICK;
            case "brown" -> Style.LOGS;
            case "tan" -> Style.STONE;
            default -> Style.GREY_STONE;
        };
        return new WallKind(style, window(mesh, top) ? WINDOW : NONE);
    }

    /** A wall object's models, as one mesh in the game's units (y down). */
    private Mesh wallMesh(ObjectDefinition def) throws IOException {
        Mesh mesh = new Mesh();
        int[] ids = def.getObjectModels();
        int[] types = def.getObjectTypes();
        if (ids == null) {
            return mesh;
        }
        for (int i = 0; i < ids.length; i++) {
            // Of a model for each shape, the straight wall's (shape 0), or else the first.
            if (types != null && types.length == ids.length && types[i] != 0 && i > 0) {
                continue;
            }
            Mesh part = models.rawMesh(models.load(ids[i]), null);
            int base = mesh.vertices.size();
            mesh.vertices.addAll(part.vertices);
            for (int[] f : part.faces) {
                mesh.faces.add(new int[]{f[0] + base, f[1] + base, f[2] + base});
            }
            mesh.colours.addAll(part.colours);
        }
        return mesh;
    }

    private static double[] averageColour(Mesh mesh) {
        double[] rgb = new double[3];
        double total = 0;
        for (int i = 0; i < mesh.faces.size(); i++) {
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
     * middle of the wall, with wall below it. The game's straight walls stand
     * along the west side of their tile, so their face lies across z and y.
     */
    private static boolean window(Mesh mesh, double top) {
        int open = 0;
        int footed = 0;
        for (double z = -40; z <= 40; z += 10) {
            if (!covered(mesh, z, -top * 0.5)) {
                open++;
            }
            if (covered(mesh, z, -top * 0.1)) {
                footed++;
            }
        }
        return open >= 3 && footed >= 6;
    }

    private static boolean covered(Mesh mesh, double z, double y) {
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
