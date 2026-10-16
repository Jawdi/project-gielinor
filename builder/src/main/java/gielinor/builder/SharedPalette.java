package gielinor.builder;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * One texture layout shared by every model of a kind (all tops, all hair),
 * so a colour the player picks needs one texture for them all, not one per
 * model: Zomboid gives a clothing item several textures (its
 * `textureChoices`) and each item its pick of them, as with its own
 * coloured clothes.
 *
 * Zomboid paints blood and dirt onto clothes body part by body part, through
 * masks laid out as its body's texture is (a splatter pattern for the left
 * hand, another for the chest...), so a face takes blood where its texture
 * coordinates fall in its own body part's pattern. The colours are laid out
 * the same way: a shade the player picks fills patches in the area of each
 * body part's pattern, and each face of it spans a whole patch, so the
 * pattern's edges cross the face and blood splatters over it as on
 * Zomboid's clothes. A face of the model's own colour (a belt, a button) is
 * small, and gets a small cell.
 *
 * Holes are cut through clothes with patterns of their own; patches keep
 * clear of them where the body part has room, as a cut leaves the clothes
 * see-through (the body beneath is hidden).
 *
 * Faces take the chosen shades through {@link #shade}'s stand-in colours,
 * their own colours otherwise.
 */
final class SharedPalette {
    /** The texture's size, as Zomboid's body texture and its masks are. */
    private static final int SIZE = 256;
    /** Pixels a shade's patch is wide. */
    private static final int PATCH = 4;
    /** Pixels a fixed colour's cell is wide. */
    private static final int CELL = 2;
    /** Patches a shade gets in each body part, for blood to vary across its faces. */
    private static final int SHADE_PATCHES = 6;
    /** How much of a patch its body part's pattern must cover, and others' may. */
    private static final double OWN_COVER = 0.2;
    private static final double OTHER_COVER = 0.1;
    /** Stand-in colours for the shades, unlikely among the game's own. */
    private static final int SHADE_BASE = 0xFE00F0;

    /** Zomboid's blood masks, by body part, in its order (BloodBodyPartType). */
    static final String[] PARTS = {"HandL", "HandR", "LArmL", "LArmR", "UArmL", "UArmR", "Chest", "Stomach",
        "Head", "Neck", "Groin", "ULegL", "ULegR", "LLegL", "LLegR", "FootL", "FootR", "Back"};

    private final String name;
    private final int shades;
    /** Which pixels are given to a patch or cell. */
    private final boolean[][] used = new boolean[SIZE][SIZE];
    /** Each body part's patches, hole-free first: corner, and 1 if a hole pattern crosses it. */
    private final Map<String, List<int[]>> patches = new HashMap<>();
    /** Each pixel's body part, or null: the first part whose pattern covers it. */
    private final String[][] partAt = new String[SIZE][SIZE];
    /** What each body part and colour has been given: corner and size. */
    private final Map<String, List<int[]>> given = new HashMap<>();
    /** Every patch and cell given out: corner, size and the colour (or stand-in) it holds. */
    private final List<int[]> filled = new ArrayList<>();
    private int taken;

    /**
     * A palette laid out over Zomboid's blood masks (`masks`: its
     * media/textures/BloodTextures), preferring room clear of its hole masks
     * (media/textures/HoleTextures beside it).
     */
    SharedPalette(String name, int shades, Path masks) throws IOException {
        this.name = name;
        this.shades = shades;
        Path holeMasks = masks.resolveSibling("HoleTextures");
        Map<String, boolean[][]> cover = new HashMap<>();
        boolean[][] holes = new boolean[SIZE][SIZE];
        for (String part : PARTS) {
            BufferedImage mask = ImageIO.read(masks.resolve("BloodMask" + part + ".png").toFile());
            BufferedImage hole = ImageIO.read(holeMasks.resolve("BloodMask" + part + ".png").toFile());
            boolean[][] on = new boolean[SIZE][SIZE];
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int argb = mask.getRGB(x, y);
                    int lum = ((argb >> 16 & 0xff) + (argb >> 8 & 0xff) + (argb & 0xff)) / 3;
                    on[x][y] = argb >>> 24 > 128 && lum > 128;
                    if (on[x][y] && partAt[x][y] == null) {
                        partAt[x][y] = part;
                    }
                    holes[x][y] |= hole.getRGB(x, y) >>> 24 != 0;
                }
            }
            cover.put(part, on);
        }
        for (int py = 0; py < SIZE; py += PATCH) {
            for (int px = 0; px < SIZE; px += PATCH) {
                String best = null;
                double bestCover = 0;
                double total = 0;
                for (String part : PARTS) {
                    double c = coverage(cover.get(part), px, py);
                    total += c;
                    if (c > bestCover) {
                        bestCover = c;
                        best = part;
                    }
                }
                if (best == null || bestCover < OWN_COVER || total - bestCover >= OTHER_COVER) {
                    continue;
                }
                boolean holed = false;
                for (int y = py; y < py + PATCH; y++) {
                    for (int x = px; x < px + PATCH; x++) {
                        holed |= holes[x][y];
                    }
                }
                patches.computeIfAbsent(best, k -> new ArrayList<>()).add(new int[]{px, py, holed ? 1 : 0});
            }
        }
        for (List<int[]> list : patches.values()) {
            list.sort(Comparator.comparingInt(p -> p[2]));
        }
    }

    private static double coverage(boolean[][] on, int px, int py) {
        int count = 0;
        for (int y = py; y < py + PATCH; y++) {
            for (int x = px; x < px + PATCH; x++) {
                count += on[x][y] ? 1 : 0;
            }
        }
        return count / (double) (PATCH * PATCH);
    }

    /** The stand-in colour for shade `i`, for faces that take a chosen colour. */
    static int shade(int i) {
        return SHADE_BASE + i;
    }

    /** A model's own colour, nudged off the stand-ins should it land on one. */
    static int fixed(int rgb) {
        rgb &= 0xffffff;
        return rgb >= SHADE_BASE && rgb < SHADE_BASE + 16 ? rgb - 16 : rgb;
    }

    String name() {
        return name;
    }

    /**
     * Texture coordinates for corner `corner` of a face of colour `colour` on
     * body part `part` (one of {@link #PARTS}); `face` picks among a shade's
     * patches. A face spans its patch or cell, inset half a pixel so
     * filtering stays inside.
     */
    double[] uv(String part, int colour, int face, int corner) {
        int rgb = colour & 0xffffff;
        boolean isShade = rgb >= SHADE_BASE && rgb < SHADE_BASE + shades;
        List<int[]> areas = given.computeIfAbsent(part + "/" + rgb, k -> {
            List<int[]> out = new ArrayList<>();
            if (isShade) {
                for (int i = 0; i < SHADE_PATCHES; i++) {
                    int[] patch = takePatch(part);
                    if (patch == null) {
                        break;
                    }
                    out.add(patch);
                }
            }
            if (out.isEmpty()) {
                out.add(takeCell(part));
            }
            for (int[] area : out) {
                filled.add(new int[]{area[0], area[1], area[2], rgb});
            }
            return out;
        });
        int[] area = areas.get(Math.floorMod(face * 31 + 7, areas.size()));
        double lo = 0.5;
        double hi = area[2] - 0.5;
        double[][] corners = {{lo, lo}, {hi, lo}, {lo, hi}};
        return new double[]{(area[0] + corners[corner][0]) / SIZE, (area[1] + corners[corner][1]) / SIZE};
    }

    /** A free patch in a body part's area, hole-free ones first: corner and size, or null. */
    private int[] takePatch(String part) {
        List<int[]> list = patches.get(part);
        while (list != null && !list.isEmpty()) {
            int[] p = list.remove(0);
            if (free(p[0], p[1], PATCH)) {
                mark(p[0], p[1], PATCH);
                return new int[]{p[0], p[1], PATCH};
            }
        }
        return null;
    }

    /** A free cell, in a body part's pattern if there's room, anywhere else if not: corner and size. */
    private int[] takeCell(String part) {
        int cells = (SIZE / CELL) * (SIZE / CELL);
        int[] fallback = null;
        int start = Math.floorMod(taken++ * 7919, cells);
        for (int n = 0; n < cells; n++) {
            int i = (start + n) % cells;
            int x = i % (SIZE / CELL) * CELL;
            int y = i / (SIZE / CELL) * CELL;
            if (!free(x, y, CELL)) {
                continue;
            }
            if (part.equals(partAt[x][y])) {
                mark(x, y, CELL);
                return new int[]{x, y, CELL};
            }
            if (fallback == null && partAt[x][y] == null) {
                fallback = new int[]{x, y, CELL};
            }
        }
        if (fallback == null) {
            throw new IllegalStateException("The " + name + " palette has run out of room");
        }
        mark(fallback[0], fallback[1], CELL);
        return fallback;
    }

    private boolean free(int x, int y, int size) {
        for (int dy = 0; dy < size; dy++) {
            for (int dx = 0; dx < size; dx++) {
                if (used[x + dx][y + dy]) {
                    return false;
                }
            }
        }
        return true;
    }

    private void mark(int x, int y, int size) {
        for (int dy = 0; dy < size; dy++) {
            for (int dx = 0; dx < size; dx++) {
                used[x + dx][y + dy] = true;
            }
        }
    }

    /** Writes one choice's texture: its shades, and every fixed colour in its place. */
    void write(Path png, int[] shadeColours) throws IOException {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        for (int[] area : filled) {
            int rgb = area[3];
            if (rgb >= SHADE_BASE && rgb < SHADE_BASE + shades) {
                rgb = shadeColours[Math.min(rgb - SHADE_BASE, shadeColours.length - 1)];
            }
            for (int dy = 0; dy < area[2]; dy++) {
                for (int dx = 0; dx < area[2]; dx++) {
                    image.setRGB(area[0] + dx, area[1] + dy, rgb);
                }
            }
        }
        ImageIO.write(image, "png", png.toFile());
    }
}
