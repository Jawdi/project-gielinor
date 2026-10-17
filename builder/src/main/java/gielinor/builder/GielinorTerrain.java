package gielinor.builder;

import java.io.IOException;
import net.runelite.cache.OverlayManager;
import net.runelite.cache.SpriteManager;
import net.runelite.cache.TextureManager;
import net.runelite.cache.UnderlayManager;
import net.runelite.cache.definitions.MapDefinition;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.definitions.UnderlayDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.item.RSTextureProvider;
import net.runelite.cache.region.RegionLoader;

/**
 * The ground of the game's world, tile by tile: what each tile on the
 * ground level looks like, sorted into the kinds of ground Zomboid has.
 *
 * A tile's ground is an underlay (a colour, for natural ground) and maybe an
 * overlay on top (a colour, or a texture such as water). A tile is sorted by
 * the colour it shows: bluish textures are water, greys are stone, yellows
 * sand, browns dirt, and greens grass, darker or lighter by their lightness.
 */
final class GielinorTerrain {
    /** Zomboid's kinds of ground (their tiles' `FloorMaterial`). */
    enum Ground {
        WATER("blends_natural_02", "Water"),
        STONE("blends_street_01", "Road_01"),
        SAND("blends_natural_01", "Sand"),
        DIRT("blends_natural_01", "Dirt"),
        DIRT_GRASS("blends_natural_01", "Dirt_Grass"),
        GRASS_DARK("blends_natural_01", "Grass_Dark"),
        GRASS_MEDIUM("blends_natural_01", "Grass_Medium"),
        GRASS_LIGHT("blends_natural_01", "Grass_Light");

        final String sheet;
        final String material;

        Ground(String sheet, String material) {
            this.sheet = sheet;
            this.material = material;
        }
    }

    /** The overlay colour the game uses for "show the underlay instead". */
    private static final int SEE_THROUGH = 0xFF00FF;

    private final RegionLoader regions;
    private final UnderlayManager underlays;
    private final OverlayManager overlays;
    private final RSTextureProvider textures;

    GielinorTerrain(Store store) throws IOException {
        regions = new RegionLoader(store, id -> null);
        underlays = new UnderlayManager(store);
        underlays.load();
        overlays = new OverlayManager(store);
        overlays.load();
        TextureManager textureManager = new TextureManager(store);
        textureManager.load();
        SpriteManager sprites = new SpriteManager(store);
        sprites.load();
        textures = new RSTextureProvider(textureManager, sprites);
    }

    /** The ground level of one map square (64 by 64 tiles), or null if the game has none there. */
    MapDefinition square(int regionX, int regionY) throws IOException {
        return regions.loadMapDef(regionX << 8 | regionY);
    }

    /**
     * Whether a tile is indoors: under a roof, as the game marks the tiles
     * whose roofs it hides when the player walks in (the 4 bit of their
     * settings).
     */
    static boolean indoor(MapDefinition.Tile tile) {
        return (tile.getSettings() & 4) != 0;
    }

    /** The kind of ground a tile is, or null for none (a gap in the world). */
    Ground ground(MapDefinition.Tile tile) {
        int underlay = tile.getUnderlayId() & 0xffff;
        int overlay = tile.getOverlayId() & 0xffff;
        if (overlay > 0) {
            OverlayDefinition def = overlays.provide(overlay - 1);
            if (def != null && def.getTexture() >= 0) {
                return byColour(textures.getAverageTextureRGB(def.getTexture()), true);
            }
            if (def != null && def.getRgbColor() != SEE_THROUGH) {
                return byColour(def.getRgbColor(), false);
            }
        }
        if (underlay > 0) {
            UnderlayDefinition def = underlays.provide(underlay - 1);
            if (def != null) {
                return byColour(def.getColor(), false);
            }
        }
        return null;
    }

    /** Sorts a colour (0xRRGGBB) into a kind of ground; `textured` for textured overlays, whose colour is their texture's. */
    static Ground byColour(int rgb, boolean textured) {
        double r = (rgb >> 16 & 0xff) / 255.0;
        double g = (rgb >> 8 & 0xff) / 255.0;
        double b = (rgb & 0xff) / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double lightness = (max + min) / 2;
        double chroma = max - min;
        // Water is a bright, strongly coloured bluish texture; the dark
        // textures, and plain blues (carpets and the like), are floors.
        if (textured) {
            return b > r && chroma > 0.4 && lightness > 0.2 ? Ground.WATER : Ground.STONE;
        }
        if (chroma < 0.08 || b > r + 0.1 && b > g) {
            return Ground.STONE;
        }
        if (lightness >= 0.45) {
            return Ground.SAND;
        }
        // The game's natural ground runs from green through olive (dried,
        // worn grass) to brown: by how green it is against red.
        double green = g / Math.max(r, 0.01);
        if (green >= 1.0) {
            return lightness < 0.22 ? Ground.GRASS_DARK : lightness < 0.33 ? Ground.GRASS_MEDIUM : Ground.GRASS_LIGHT;
        }
        return green >= 0.85 ? Ground.DIRT_GRASS : Ground.DIRT;
    }
}
