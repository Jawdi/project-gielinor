package gielinor.builder;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/** Reads the exported artifacts themselves, independently of the drawing sink. */
final class WorldArtProof {
    private WorldArtProof() { }
    record Result(int sprites, int pages, int depthFrames, String contentDigest) { }
    private record Frame(int sprite, int x, int y, int width, int height) { }
    private static final byte[] NONE = new byte[32];

    static Result inspect(Path media, int sprites) throws Exception {
        if (sprites < 1 || sprites > 2_000_000) throw new IOException("Invalid proof sprite count");
        ImageIO.setUseCache(false);
        var crops = new int[sprites][];
        var colours = new byte[sprites][];
        var depths = new byte[sprites][];
        int pages;
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(media.resolve("texturepacks/original_geometry_tiles.pack"))))) {
            if (!Arrays.equals(in.readNBytes(4), new byte[]{'P','Z','P','K'}) || integer(in) != 1)
                throw new IOException("Expected a PZPK version 1 texture pack");
            pages = bounded(integer(in), 1, sprites, "page count");
            int total = 0;
            for (int page = 0; page < pages; page++) {
                string(in);
                int count = bounded(integer(in), 1, sprites - total, "page sprite count");
                bounded(integer(in), 0, 1, "alpha flag");
                var entries = new ArrayList<Frame>(count);
                for (int at = 0; at < count; at++) {
                    int sprite = index(string(in), sprites);
                    if (colours[sprite] != null || crops[sprite] != null) throw new IOException("Duplicate texture sprite");
                    int x = integer(in), y = integer(in), w = integer(in), h = integer(in);
                    int ox = integer(in), oy = integer(in), fw = integer(in), fh = integer(in);
                    if (fw != 128 || fh != 256 || w < 1 || h < 1 || ox < 0 || oy < 0 || ox + w > fw || oy + h > fh)
                        throw new IOException("Invalid native sprite frame");
                    crops[sprite] = new int[]{ox, oy, w, h};
                    entries.add(new Frame(sprite, x, y, w, h));
                }
                int length = bounded(integer(in), 1, 256 * 1024 * 1024, "page PNG length");
                byte[] png = in.readNBytes(length);
                if (png.length != length) throw new EOFException("Truncated texture PNG");
                BufferedImage atlas = ImageIO.read(new ByteArrayInputStream(png));
                if (atlas == null || atlas.getWidth() > 2048 || atlas.getHeight() > 2048) throw new IOException("Invalid colour atlas");
                for (var entry : entries) {
                    if (entry.x() < 0 || entry.y() < 0 || (long) entry.x() + entry.width() > atlas.getWidth()
                            || (long) entry.y() + entry.height() > atlas.getHeight()) throw new IOException("Texture rectangle leaves its atlas");
                    colours[entry.sprite()] = pixelKey(atlas.getRGB(entry.x(), entry.y(), entry.width(), entry.height(), null, 0, entry.width()),
                        entry.width(), entry.height(), false);
                }
                total += count;
            }
            if (total != sprites || in.read() != -1) throw new IOException("Texture pack sprite coverage differs");
        }
        int sourceFrames = 0;
        int sheets = (sprites + 511) / 512;
        for (int sheet = 0; sheet < sheets; sheet++) {
            Path path = media.resolve("depthmaps/DEPTH_" + WorldArtWriter.sheet(sheet) + ".png");
            BufferedImage atlas = ImageIO.read(path.toFile());
            int count = Math.min(512, sprites - sheet * 512);
            if (atlas == null || atlas.getWidth() != 1024 || atlas.getHeight() != ((count + 7) / 8) * 256)
                throw new IOException("Invalid depth atlas dimensions: " + path.getFileName());
            for (int tile = 0; tile < count; tile++) {
                int[] pixels = atlas.getRGB(tile % 8 * 128, tile / 8 * 256, 128, 256, null, 0, 128);
                boolean shows = false;
                for (int pixel : pixels) if (pixel >>> 24 != 0) { shows = true; break; }
                if (shows) depths[sheet * 512 + tile] = pixelKey(pixels, 128, 256, true);
            }
        }
        var aliases = new int[sprites];
        Arrays.fill(aliases, -1);
        var pattern = Pattern.compile("\\s*(original_geometry_\\d+_\\d+)\\s*=\\s*(original_geometry_\\d+_\\d+)\\s*,\\s*");
        Path assignments = media.resolve("tileDepthTextureAssignments.txt");
        for (String line : Files.isRegularFile(assignments) ? Files.readAllLines(assignments) : List.<String>of()) {
            var match = pattern.matcher(line);
            if (!match.matches()) {
                if (line.contains("original_geometry_")) throw new IOException("Invalid depth assignment");
                continue;
            }
            int source = index(match.group(1), sprites), target = index(match.group(2), sprites);
            if (aliases[source] >= 0 || source == target) throw new IOException("Duplicate or cyclic depth assignment");
            aliases[source] = target;
        }
        var digest = MessageDigest.getInstance("SHA-256");
        for (int sprite = 0; sprite < sprites; sprite++) {
            if (colours[sprite] == null) throw new IOException("Missing colour sprite");
            int target = sprite;
            for (int hops = 0; aliases[target] >= 0; hops++) {
                if (hops >= sprites) throw new IOException("Depth assignment cycle");
                target = aliases[target];
            }
            if (target != sprite && depths[target] == null) throw new IOException("Depth alias targets an empty frame");
            if (depths[target] != null) sourceFrames++;
            var crop = ByteBuffer.allocate(16);
            for (int value : crops[sprite]) crop.putInt(value);
            digest.update(crop.array());
            digest.update(colours[sprite]);
            digest.update(depths[target] == null ? NONE : depths[target]);
        }
        return new Result(sprites, pages, sourceFrames, HexFormat.of().formatHex(digest.digest()));
    }

    private static byte[] pixelKey(int[] pixels, int width, int height, boolean depth) throws Exception {
        var buffer = ByteBuffer.allocate(8 + pixels.length * 4).putInt(width).putInt(height);
        for (int pixel : pixels) buffer.putInt(depth ? (pixel >>> 24 != 0 ? (pixel & 255) + 1 : 0) : pixel);
        return MessageDigest.getInstance("SHA-256").digest(buffer.array());
    }
    private static int index(String name, int count) throws IOException {
        try {
            int index = WorldArtBuilder.index(name);
            if (index < 0 || index >= count || !WorldArtWriter.name(index).equals(name)) throw new IllegalArgumentException();
            return index;
        } catch (RuntimeException e) { throw new IOException("Invalid world sprite name: " + name, e); }
    }
    private static int integer(DataInputStream in) throws IOException { return Integer.reverseBytes(in.readInt()); }
    private static int bounded(int value, int min, int max, String label) throws IOException {
        if (value < min || value > max) throw new IOException("Invalid " + label);
        return value;
    }
    private static String string(DataInputStream in) throws IOException {
        int size = bounded(integer(in), 0, 4096, "pack string length");
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) throw new EOFException("Truncated pack string");
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
}
