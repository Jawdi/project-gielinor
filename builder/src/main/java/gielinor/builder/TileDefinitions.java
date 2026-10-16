package gielinor.builder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zomboid's tiles and their properties, read from the install's
 * `media/newtiledefinitions.tiles`: the material each floor is
 * (`FloorMaterial` Grass_Dark, Sand, Water...), whether it's water, and so
 * on. The map's ground is chosen by these properties, not by tile names.
 *
 * The file: "tdef", a version (1), the number of tilesheets, then each
 * sheet's name, image name, width, height, number and tile count, and each
 * tile's properties as a count and that many name and value pairs. Numbers
 * are little-endian ints; strings end at a newline.
 */
final class TileDefinitions {
    /** Each tile's properties, by tile name (sheet name, an underscore and its index). */
    final Map<String, Map<String, String>> tiles = new LinkedHashMap<>();

    static TileDefinitions read(Path install) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(Files.readAllBytes(install.resolve("media/newtiledefinitions.tiles")))
            .order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        in.get(magic);
        if (!new String(magic, StandardCharsets.US_ASCII).equals("tdef")) {
            throw new IOException("Not a tile definitions file");
        }
        int version = in.getInt();
        if (version != 1) {
            throw new IOException("Tile definitions version " + version + " isn't supported");
        }
        TileDefinitions defs = new TileDefinitions();
        int sheets = in.getInt();
        for (int s = 0; s < sheets; s++) {
            String name = string(in).trim();
            string(in);
            in.getInt();
            in.getInt();
            in.getInt();
            int count = in.getInt();
            for (int t = 0; t < count; t++) {
                int props = in.getInt();
                Map<String, String> map = new LinkedHashMap<>();
                for (int p = 0; p < props; p++) {
                    map.put(string(in), string(in));
                }
                defs.tiles.put(name + "_" + t, map);
            }
        }
        return defs;
    }

    /** Tiles of a sheet whose `FloorMaterial` is `material`, in the sheet's order. */
    List<String> floors(String sheet, String material) {
        List<String> out = new ArrayList<>();
        tiles.forEach((name, props) -> {
            if (name.startsWith(sheet + "_") && material.equals(props.get("FloorMaterial"))) {
                out.add(name);
            }
        });
        return out;
    }

    private static String string(ByteBuffer in) {
        int start = in.position();
        while (in.get() != '\n') {
            // Up to and past the newline.
        }
        return new String(in.array(), start, in.position() - start - 1, StandardCharsets.UTF_8);
    }
}
