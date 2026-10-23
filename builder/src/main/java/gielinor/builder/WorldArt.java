package gielinor.builder;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import net.runelite.cache.definitions.ObjectDefinition;

/**
 * Mesh and image helpers the world art is drawn with, shared by the
 * converter and the shipped recipe renderer (ArtRecipes): object meshes,
 * face selection, glass detection and infill, tree layers, image copies,
 * native window and door hole masks, and the painter that turns meshes into
 * sprite pieces.
 */
final class WorldArt {
    private WorldArt() {
    }

    /**
     * Turns a placed mesh into sprite pieces. The shipped builder paints
     * directly; the converter installs its disk-backed art caches, which
     * return the same pixels for the same mesh.
     */
    interface Painter {
        List<PrototypeSceneArt.Piece> scene(String sourceKey, Mesh mesh, boolean mirror, int turn, int width, int depth) throws Exception;

        List<PrototypeWallArt.Piece> wall(String sourceKey, Mesh mesh, boolean mirror, int turn, int side) throws Exception;
    }

    static final Painter DIRECT = new Painter() {
        public List<PrototypeSceneArt.Piece> scene(String sourceKey, Mesh mesh, boolean mirror, int turn, int width, int depth) {
            return PrototypeSceneArt.draw(mesh, mirror, turn, 0, 0, width, depth);
        }

        public List<PrototypeWallArt.Piece> wall(String sourceKey, Mesh mesh, boolean mirror, int turn, int side) {
            return PrototypeWallArt.draw(mesh, mirror, turn, 0, 0, 1, 1, side);
        }
    };

    static volatile Painter painter = DIRECT;

    // ---- object meshes ----

    record ArtKey(ObjectDefinition def, int shape) {
        @Override
        public boolean equals(Object o) {
            return o instanceof ArtKey k && k.def == def && k.shape == shape;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(def) * 31 + shape;
        }
    }

    /**
     * ModelExporter.objectArt remembered per object definition and shape: one
     * placement asks for the same mesh several times. Every call gets its own
     * deep copy, because callers move and squash vertices in place. Keyed by
     * identity: definitions are the ObjectManager's single instances, and their
     * equals hashes every field. The 8192 most recently used meshes are kept.
     */
    static final Map<ArtKey, Mesh> ART = Collections.synchronizedMap(new LinkedHashMap<>(1024, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ArtKey, Mesh> eldest) {
            return size() > 8192;
        }
    });

    static Mesh art(ModelExporter models, ObjectDefinition def, int shape) throws IOException {
        var key = new ArtKey(def, shape);
        var known = ART.get(key);
        if (known == null) {
            known = models.objectArt(def, shape);
            ART.put(key, known);
        }
        return copyMesh(known);
    }

    static Mesh copyMesh(Mesh mesh) {
        var copy = new Mesh();
        for (var v : mesh.vertices) copy.vertices.add(v.clone());
        for (var f : mesh.faces) copy.faces.add(f.clone());
        copy.colours.addAll(mesh.colours);
        copy.sourceColours.addAll(mesh.sourceColours);
        copy.groups.addAll(mesh.groups);
        copy.alphas.addAll(mesh.alphas);
        for (var uv : mesh.uvs) copy.uvs.add(uv.clone());
        return copy;
    }

    /** Keep face colours/materials and the source geometry; split a movable pane from its frame. */
    static Mesh selectFaces(Mesh original, java.util.function.IntPredicate keep) {
        Mesh out = new Mesh();
        for (var v : original.vertices) out.vertices.add(v.clone());
        for (int f = 0; f < original.faces.size(); f++) {
            if (keep.test(f)) {
                out.faces.add(original.faces.get(f).clone());
                out.colours.add(original.colours.get(f));
                out.alphas.add(f < original.alphas.size() ? original.alphas.get(f) : 0);
            }
        }
        return out;
    }

    // ---- glass ----

    /**
     * Glass is a bright saturated green or blue, or translucent. The hue test
     * alone also took dark shaded moss green (RGB 2d3d2d, lightness 14) and
     * blue-grey stone, turning Morytania timber walls, stone walls and iron
     * railings into windows.
     */
    static boolean glazingColour(int colour, int alpha) {
        int r = colour >> 16 & 255, g = colour >> 8 & 255, b = colour & 255;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return (g > r * 1.3 && g > b * 1.3 && g > 50 || b > r * 1.3 && b > g * 1.15 && b > 60) && max >= 100 && (max - min >= 60 || alpha > 0);
    }

    /**
     * A building can consist entirely of window sections. When no plain wall
     * donor exists, retain this section's geometry and use its own opaque
     * masonry colour to infill glazing before cutting the native aperture.
     */
    static Mesh selfWindowMaterial(Mesh mesh) {
        var colours = new TreeMap<Integer, Double>();
        double glazingArea = 0;
        for (int f = 0; f < mesh.faces.size(); f++) {
            var indices = mesh.faces.get(f);
            var a = mesh.vertices.get(indices[0]);
            var b = mesh.vertices.get(indices[1]);
            var c = mesh.vertices.get(indices[2]);
            double area = Math.abs((b[2] - a[2]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[2] - a[2])) * .5;
            int colour = mesh.colours.get(f), r = colour >> 16 & 255, g = colour >> 8 & 255, blue = colour & 255;
            if (glazingColour(colour, mesh.alphas.get(f))) {
                glazingArea += area;
                continue;
            }
            int min = Math.min(r, Math.min(g, blue)), max = Math.max(r, Math.max(g, blue));
            // Reject black backing, bright trim, translucent faces and strongly
            // coloured details as masonry evidence. Area is projected on the
            // source wall plane, excluding horizontal caps and depth edges.
            if (mesh.alphas.get(f) == 0 && min >= 48 && max <= 220 && max - min <= 48 && area > 0) colours.merge(colour, area, Double::sum);
        }
        double masonryArea = colours.values().stream().mapToDouble(Double::doubleValue).sum();
        if (glazingArea < 1024 || masonryArea < 2048) return null;
        int colour = colours.entrySet().stream().max(Map.Entry.<Integer, Double>comparingByValue().thenComparingInt(Map.Entry::getKey)).orElseThrow().getKey();
        for (int f = 0; f < mesh.faces.size(); f++) {
            if (glazingColour(mesh.colours.get(f), mesh.alphas.get(f))) {
                mesh.colours.set(f, colour);
                mesh.alphas.set(f, 0);
            }
        }
        return mesh;
    }

    // ---- tree layers ----

    static List<double[]> clip(List<double[]> input, double at, boolean above) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            var a = input.get(i);
            var b = input.get((i + 1) % input.size());
            double ah = -a[1] * .8, bh = -b[1] * .8;
            boolean ai = above ? ah >= at : ah <= at, bi = above ? bh >= at : bh <= at;
            if (ai) out.add(a);
            if (ai != bi) {
                double t = (at - ah) / (bh - ah);
                out.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t});
            }
        }
        return out;
    }

    /** Split rendering at walking height, preserving source face colours and alpha. */
    static Mesh heightLayer(Mesh original, double height, boolean above) {
        var out = new Mesh();
        for (int f = 0; f < original.faces.size(); f++) {
            List<double[]> polygon = new ArrayList<>();
            for (int index : original.faces.get(f)) polygon.add(original.vertices.get(index));
            polygon = clip(polygon, height, above);
            for (int i = 1; i + 1 < polygon.size(); i++) {
                int base = out.vertices.size();
                out.vertices.add(polygon.get(0).clone());
                out.vertices.add(polygon.get(i).clone());
                out.vertices.add(polygon.get(i + 1).clone());
                out.faces.add(new int[]{base, base + 1, base + 2});
                out.colours.add(original.colours.get(f));
                out.alphas.add(f < original.alphas.size() ? original.alphas.get(f) : 0);
            }
        }
        return out;
    }

    // ---- images ----

    static BufferedImage copyImage(BufferedImage source) {
        return ArgbPixels.normalized(source);
    }

    /** Where the native window and door hole masks (native-window-hole-W.png and so on) are read from. */
    static volatile Path holeMasks = Path.of(".agent-notes");
    static final Map<String, int[]> HOLES = Collections.synchronizedMap(new HashMap<>());

    /**
     * Reads the window and door hole masks from Zomboid's own Tiles2x pack,
     * as extract-window-masks.py does for the converter: each hole is a plain
     * exterior wall's alpha minus the alpha of the same wall with a window or
     * door frame (walls_exterior_house_02 frames 0 and 1 against 8 to 11),
     * clamped at zero. A player's build uses this instead of mask files.
     */
    static void holesFromGame(Path zomboid) throws IOException {
        var wanted = new HashSet<String>();
        for (int n : new int[]{0, 1, 8, 9, 10, 11}) wanted.add("walls_exterior_house_02_" + n);
        var alphas = new HashMap<String, int[]>();
        try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(
                java.nio.file.Files.newInputStream(zomboid.resolve("media/texturepacks/Tiles2x.pack")), 1 << 20))) {
            if (in.readInt() != 0x505A504B || readInt(in) != 1) throw new IOException("Tiles2x.pack is not a version 1 PZPK pack");
            for (int pages = readInt(in), page = 0; page < pages && alphas.size() < wanted.size(); page++) {
                readString(in);
                int count = readInt(in);
                readInt(in);
                var entries = new ArrayList<Object[]>();
                for (int e = 0; e < count; e++) {
                    String name = readString(in);
                    int[] frame = new int[8];
                    for (int k = 0; k < 8; k++) frame[k] = readInt(in);
                    if (wanted.contains(name)) entries.add(new Object[]{name, frame});
                }
                int length = readInt(in);
                if (entries.isEmpty()) {
                    in.skipNBytes(length);
                    continue;
                }
                var atlas = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(in.readNBytes(length)));
                for (Object[] entry : entries) {
                    int[] f = (int[]) entry[1];
                    int[] alpha = new int[128 * 256];
                    for (int y = 0; y < f[3]; y++) for (int x = 0; x < f[2]; x++) {
                        int tx = f[4] + x, ty = f[5] + y;
                        if (tx < 128 && ty < 256) alpha[tx + ty * 128] = atlas.getRGB(f[0] + x, f[1] + y) >>> 24;
                    }
                    alphas.put((String) entry[0], alpha);
                }
            }
        }
        if (alphas.size() != wanted.size()) throw new IOException("Tiles2x.pack lacks the exterior wall frames the holes are cut from");
        for (Object[] hole : new Object[][]{{"window-hole-W", 0, 8}, {"window-hole-N", 1, 9}, {"door-hole-W", 0, 10}, {"door-hole-N", 1, 11}}) {
            int[] solid = alphas.get("walls_exterior_house_02_" + hole[1]), frame = alphas.get("walls_exterior_house_02_" + hole[2]);
            int[] alpha = new int[128 * 256];
            boolean any = false;
            for (int i = 0; i < alpha.length; i++) any |= (alpha[i] = Math.max(0, solid[i] - frame[i])) != 0;
            if (!any) throw new IOException("Empty " + hole[0] + " mask");
            HOLES.put((String) hole[0], alpha);
        }
    }

    private static int readInt(java.io.DataInputStream in) throws IOException {
        return Integer.reverseBytes(in.readInt());
    }

    private static String readString(java.io.DataInputStream in) throws IOException {
        return new String(in.readNBytes(readInt(in)), java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** A native aperture mask's alpha per pixel, read once. */
    static int[] holeAlpha(String name) {
        return HOLES.computeIfAbsent(name, k -> {
            try {
                var image = javax.imageio.ImageIO.read(holeMasks.resolve("native-" + k + ".png").toFile());
                int[] alpha = new int[128 * 256];
                for (int py = 0; py < 256; py++) for (int px = 0; px < 128; px++) alpha[px + py * 128] = image.getRGB(px, py) >>> 24;
                return alpha;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }
}
