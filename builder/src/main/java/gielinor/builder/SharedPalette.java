package gielinor.builder;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * Zomboid paints blood, dirt and holes onto clothes body part by body part,
 * through masks laid out as its body's texture is (a splatter pattern for
 * the left hand, another for the chest...), so a face only takes blood where
 * its texture coordinates fall in its own body part's pattern. The colours
 * are laid out the same way: each face's colour sits in cells inside the
 * pattern of the body part it covers, a shade the player picks in several
 * cells (so blood on it varies from face to face), a fixed colour in one.
 *
 * Holes are cut through clothes the same way, with a pattern per body part,
 * which would leave a face missing and the clothes see-through (the body
 * beneath is hidden, so it can't poke through). So cells also keep out of
 * their body part's hole pattern: holes count but don't show.
 *
 * Faces take the chosen shades through {@link #shade}'s stand-in colours,
 * their own colours otherwise.
 */
final class SharedPalette {
    /** The texture's size, as Zomboid's body texture and its masks are. */
    private static final int SIZE = 256;
    /** Pixels a cell is wide; coordinates point at its middle so filtering stays inside it. */
    private static final int CELL = 2;
    /** Cells a shade gets in each body part, for blood to vary across its faces. */
    private static final int SHADE_CELLS = 4;
    /** Stand-in colours for the shades, unlikely among the game's own. */
    private static final int SHADE_BASE = 0xFE00F0;

    /** Zomboid's blood masks, by body part, in its order (BloodBodyPartType). */
    static final String[] PARTS = {"HandL", "HandR", "LArmL", "LArmR", "UArmL", "UArmR", "Chest", "Stomach",
        "Head", "Neck", "Groin", "ULegL", "ULegR", "LLegL", "LLegR", "FootL", "FootR", "Back"};

    private final String name;
    private final int shades;
    /** Free cells by body part: cell corners inside the part's mask. */
    private final Map<String, List<int[]>> free;
    /** Free cells in no part's mask, for when a part runs out. */
    private final List<int[]> spare;
    /** The cells each body part and colour has been given. */
    private final Map<String, List<int[]>> cells = new HashMap<>();
    /** Every cell and the colour (or stand-in) it holds. */
    private final List<int[]> filled = new ArrayList<>();
    private int taken;

    /**
     * A palette laid out over Zomboid's blood masks (`masks`: its
     * media/textures/BloodTextures) and clear of its hole masks
     * (media/textures/HoleTextures beside it).
     */
    SharedPalette(String name, int shades, Path masks) throws IOException {
        Path holes = masks.resolveSibling("HoleTextures");
        this.name = name;
        this.shades = shades;
        boolean[][] taken = new boolean[SIZE / CELL][SIZE / CELL];
        free = new HashMap<>();
        for (String part : PARTS) {
            BufferedImage mask = ImageIO.read(masks.resolve("BloodMask" + part + ".png").toFile());
            BufferedImage hole = ImageIO.read(holes.resolve("BloodMask" + part + ".png").toFile());
            List<int[]> inside = new ArrayList<>();
            for (int cy = 0; cy < SIZE / CELL; cy++) {
                for (int cx = 0; cx < SIZE / CELL; cx++) {
                    if (!taken[cx][cy] && solid(mask, cx * CELL, cy * CELL) && clear(hole, cx * CELL, cy * CELL)) {
                        inside.add(new int[]{cx, cy});
                        taken[cx][cy] = true;
                    }
                }
            }
            free.put(part, inside);
        }
        spare = new ArrayList<>();
        for (int cy = 0; cy < SIZE / CELL; cy++) {
            for (int cx = 0; cx < SIZE / CELL; cx++) {
                if (!taken[cx][cy]) {
                    spare.add(new int[]{cx, cy});
                }
            }
        }
    }

    /** Whether a cell's every pixel shows in a mask (white and opaque). */
    private static boolean solid(BufferedImage mask, int x, int y) {
        for (int dy = 0; dy < CELL; dy++) {
            for (int dx = 0; dx < CELL; dx++) {
                int argb = mask.getRGB(x + dx, y + dy);
                int alpha = argb >>> 24;
                int lum = ((argb >> 16 & 0xff) + (argb >> 8 & 0xff) + (argb & 0xff)) / 3;
                if (alpha < 128 || lum < 128) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether no pixel of a cell shows in a mask (fully transparent). */
    private static boolean clear(BufferedImage mask, int x, int y) {
        for (int dy = 0; dy < CELL; dy++) {
            for (int dx = 0; dx < CELL; dx++) {
                if (mask.getRGB(x + dx, y + dy) >>> 24 != 0) {
                    return false;
                }
            }
        }
        return true;
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
     * Texture coordinates for a face of colour `colour` on body part `part`
     * (one of {@link #PARTS}); `face` picks among a shade's cells.
     */
    double[] uv(String part, int colour, int face) {
        int rgb = colour & 0xffffff;
        boolean isShade = rgb >= SHADE_BASE && rgb < SHADE_BASE + shades;
        List<int[]> given = cells.computeIfAbsent(part + "/" + rgb, k -> {
            List<int[]> out = new ArrayList<>();
            for (int i = 0; i < (isShade ? SHADE_CELLS : 1); i++) {
                int[] cell = take(part);
                filled.add(new int[]{cell[0], cell[1], rgb});
                out.add(cell);
            }
            return out;
        });
        int[] cell = given.get(Math.floorMod(face * 31 + 7, given.size()));
        return new double[]{(cell[0] * CELL + CELL / 2.0) / SIZE, (cell[1] * CELL + CELL / 2.0) / SIZE};
    }

    private int[] take(String part) {
        List<int[]> inside = free.get(part);
        if (inside != null && !inside.isEmpty()) {
            // Spread out over the pattern rather than filling one corner.
            return inside.remove(Math.floorMod(taken++ * 7919, inside.size()));
        }
        if (spare.isEmpty()) {
            throw new IllegalStateException("The " + name + " palette has run out of cells");
        }
        return spare.remove(spare.size() - 1);
    }

    /** Writes one choice's texture: its shades, and every fixed colour in its cells. */
    void write(Path png, int[] shadeColours) throws IOException {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        for (int[] cell : filled) {
            int rgb = cell[2];
            if (rgb >= SHADE_BASE && rgb < SHADE_BASE + shades) {
                rgb = shadeColours[Math.min(rgb - SHADE_BASE, shadeColours.length - 1)];
            }
            for (int dy = 0; dy < CELL; dy++) {
                for (int dx = 0; dx < CELL; dx++) {
                    image.setRGB(cell[0] * CELL + dx, cell[1] * CELL + dy, rgb);
                }
            }
        }
        ImageIO.write(image, "png", png.toFile());
    }
}
