package gielinor.builder;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * One palette layout shared by every model of a kind (all tops, all hair),
 * so a colour the player picks needs one texture for them all, not one per
 * model: Zomboid gives a clothing item several textures (its
 * `textureChoices`) and each item its pick of them, as with its own
 * coloured clothes.
 *
 * The first cells are the shades a choice sets (a clothing colour and its
 * second shade, a skin colour); faces take them through {@link #shade}'s
 * stand-in colours. The rest are the models' own fixed colours.
 */
final class SharedPalette implements ColourMap {
    /** Pixels a cell is wide. */
    private static final int CELL = 4;
    /** Cells across; the layout can't grow once models point into it. */
    private static final int SIDE = 32;
    /** Stand-in colours for the shades, unlikely among the game's own. */
    private static final int SHADE_BASE = 0xFE00F0;

    private final String name;
    private final int shades;
    private final Map<Integer, Integer> cells = new LinkedHashMap<>();

    SharedPalette(String name, int shades) {
        this.name = name;
        this.shades = shades;
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

    @Override
    public double[] uv(int rgb) {
        rgb &= 0xffffff;
        int cell;
        if (rgb >= SHADE_BASE && rgb < SHADE_BASE + shades) {
            cell = rgb - SHADE_BASE;
        } else {
            cell = cells.computeIfAbsent(rgb, k -> shades + cells.size());
            if (cell >= SIDE * SIDE) {
                throw new IllegalStateException("The " + name + " palette has run out of cells");
            }
        }
        return new double[]{(cell % SIDE + 0.5) / SIDE, (cell / SIDE + 0.5) / SIDE};
    }

    /** Writes one choice's texture: its shades, and every fixed colour in its place. */
    void write(Path png, int[] shadeColours) throws IOException {
        int size = SIDE * CELL;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < shades; i++) {
            fill(image, i, shadeColours[Math.min(i, shadeColours.length - 1)]);
        }
        for (Map.Entry<Integer, Integer> e : cells.entrySet()) {
            fill(image, e.getValue(), e.getKey());
        }
        ImageIO.write(image, "png", png.toFile());
    }

    private static void fill(BufferedImage image, int cell, int rgb) {
        int cx = cell % SIDE * CELL;
        int cy = cell / SIDE * CELL;
        for (int y = 0; y < CELL; y++) {
            for (int x = 0; x < CELL; x++) {
                image.setRGB(cx + x, cy + y, rgb);
            }
        }
    }
}
