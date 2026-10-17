package gielinor.builder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One cell of a Build 42 Zomboid map: 256 by 256 squares in chunks of 8 by
 * 8, written as the three files the game loads a cell from. The layout is
 * the one the game's own map converter writes (`zombie.pot`):
 *
 * <ul>
 * <li>`X_Y.lotheader`: "LOTH", version 1, the tile names the cell uses (each
 * ending in a newline), the chunk's width and height in squares, its lowest
 * and highest level, its rooms and buildings, then a zombie density byte for
 * each chunk, column by column.</li>
 * <li>`world_X_Y.lotpack`: "LOTP", version 1, the chunk width, a table of
 * where each chunk's data starts (8 bytes a chunk, column by column), then
 * each chunk's squares level by level, column by column: a count of tiles
 * plus one, a room (-1 for none) and the tiles as indices into the header's
 * list. A run of empty squares is -1 and its length.</li>
 * <li>`chunkdata_X_Y.bin`: for pathfinding, big-endian: version 1, then for
 * each chunk row by row its type (0 open, 1 solid, 3 water, 4 room, or 2
 * mixed followed by a byte for each square: 1 solid, 2 wall north, 4 wall
 * west, 8 water, 16 room).</li>
 * </ul>
 *
 * Numbers are little-endian except in chunk data.
 */
final class MapCell {
    static final int SIZE = 256;
    static final int CHUNK = 8;
    static final int CHUNKS = SIZE / CHUNK;

    static final int BIT_SOLID = 1;
    static final int BIT_WALL_NORTH = 2;
    static final int BIT_WALL_WEST = 4;
    static final int BIT_WATER = 8;

    final int cellX;
    final int cellY;
    /** Each square's tiles at ground level, floor first, by x + y * SIZE (null for none). */
    private final String[][] squares = new String[SIZE * SIZE][];
    /** Each square's pathfinding bits. */
    private final byte[] bits = new byte[SIZE * SIZE];
    /** Zombies per chunk, 0 to 255, by chunk x + y * CHUNKS. */
    private final byte[] zombies = new byte[CHUNKS * CHUNKS];

    MapCell(int cellX, int cellY) {
        this.cellX = cellX;
        this.cellY = cellY;
    }

    /** Sets a square's tiles (cell-local coordinates), floor first. */
    void set(int x, int y, String... tiles) {
        squares[x + y * SIZE] = tiles;
    }

    void setBits(int x, int y, int squareBits) {
        bits[x + y * SIZE] = (byte) squareBits;
    }

    void setZombies(int chunkX, int chunkY, int density) {
        zombies[chunkX + chunkY * CHUNKS] = (byte) Math.max(0, Math.min(255, density));
    }

    void write(Path dir) throws IOException {
        Map<String, Integer> index = new HashMap<>();
        List<String> used = new ArrayList<>();
        for (String[] tiles : squares) {
            if (tiles != null) {
                for (String t : tiles) {
                    if (!index.containsKey(t)) {
                        index.put(t, used.size());
                        used.add(t);
                    }
                }
            }
        }
        Files.write(dir.resolve(cellX + "_" + cellY + ".lotheader"), header(used));
        Files.write(dir.resolve("world_" + cellX + "_" + cellY + ".lotpack"), pack(index));
        Files.write(dir.resolve("chunkdata_" + cellX + "_" + cellY + ".bin"), chunkData());
    }

    private byte[] header(List<String> used) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer ints = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        out.writeBytes("LOTH".getBytes(StandardCharsets.US_ASCII));
        writeInt(out, ints, 1);
        writeInt(out, ints, used.size());
        for (String t : used) {
            out.writeBytes(t.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
        }
        writeInt(out, ints, CHUNK);
        writeInt(out, ints, CHUNK);
        writeInt(out, ints, 0);
        writeInt(out, ints, 0);
        writeInt(out, ints, 0);
        writeInt(out, ints, 0);
        for (int x = 0; x < CHUNKS; x++) {
            for (int y = 0; y < CHUNKS; y++) {
                out.write(zombies[x + y * CHUNKS]);
            }
        }
        return out.toByteArray();
    }

    private byte[] pack(Map<String, Integer> index) {
        int tableStart = 12;
        ByteBuffer out = ByteBuffer.allocate(64 * 1024 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        out.put("LOTP".getBytes(StandardCharsets.US_ASCII));
        out.putInt(1);
        out.putInt(CHUNK);
        out.position(tableStart + CHUNKS * CHUNKS * 8);
        for (int cx = 0; cx < CHUNKS; cx++) {
            for (int cy = 0; cy < CHUNKS; cy++) {
                out.putLong(tableStart + (cx * CHUNKS + cy) * 8, out.position());
                int empty = 0;
                for (int x = 0; x < CHUNK; x++) {
                    for (int y = 0; y < CHUNK; y++) {
                        String[] tiles = squares[cx * CHUNK + x + (cy * CHUNK + y) * SIZE];
                        if (tiles == null || tiles.length == 0) {
                            empty++;
                            continue;
                        }
                        if (empty > 0) {
                            out.putInt(-1);
                            out.putInt(empty);
                            empty = 0;
                        }
                        out.putInt(tiles.length + 1);
                        out.putInt(-1);
                        for (String t : tiles) {
                            out.putInt(index.get(t));
                        }
                    }
                }
                if (empty > 0) {
                    out.putInt(-1);
                    out.putInt(empty);
                }
            }
        }
        byte[] result = new byte[out.position()];
        out.get(0, result);
        return result;
    }

    private byte[] chunkData() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(1);
        int squares = CHUNK * CHUNK;
        byte[] chunk = new byte[squares];
        for (int cy = 0; cy < CHUNKS; cy++) {
            for (int cx = 0; cx < CHUNKS; cx++) {
                boolean same = true;
                for (int y = 0; y < CHUNK; y++) {
                    for (int x = 0; x < CHUNK; x++) {
                        chunk[x + y * CHUNK] = bits[cx * CHUNK + x + (cy * CHUNK + y) * SIZE];
                        same &= chunk[x + y * CHUNK] == chunk[0];
                    }
                }
                int whole = chunk[0];
                if (same && (whole == 0 || whole == BIT_SOLID || whole == BIT_WATER || whole == 16)) {
                    out.writeByte(whole == 0 ? 0 : whole == BIT_SOLID ? 1 : whole == BIT_WATER ? 3 : 4);
                } else {
                    out.writeByte(2);
                    out.write(chunk);
                }
            }
        }
        return bytes.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, ByteBuffer buffer, int v) {
        buffer.clear();
        buffer.putInt(v);
        out.write(buffer.array(), 0, 4);
    }
}
