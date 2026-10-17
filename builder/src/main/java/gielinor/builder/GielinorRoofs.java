package gielinor.builder;

import java.util.List;

/**
 * The world's roofs, as Zomboid's pitched roofs: over each roof (the squares
 * with a roof on one level, side by side) a hip roof, its slopes rising from
 * every edge to a flat top once it's more than three squares in.
 *
 * <p>Zomboid draws a roof only as seen from the south-east, as its building
 * editor lays them: the south and east slopes (each a third of a level high
 * per square, so three squares to the top of a level), the hip where they
 * meet, the half slopes at the south-west and north-east corners, the
 * valley where a roof turns an inside corner, and no north or west slopes,
 * which face away. A square's height is how far it is from the roof's edge,
 * a step to any of the eight squares around it counting one, so a roof
 * turning a corner gets its valley; the piece it takes follows from which
 * of its neighbours stand lower.
 */
final class GielinorRoofs {
    /**
     * Roof styles, by the colour of the game's roof (see
     * {@link GielinorObjects}): slate (grey or blue), clay tiles (red),
     * wooden boards (brown, thatch), pale stone tiles (beige) and green
     * tiles. Each is a sheet and the first of its tiles, laid out as
     * Zomboid's roof sheets are in groups of 32.
     */
    static final int SLATE = 0;
    static final int CLAY = 1;
    static final int BOARDS = 2;
    static final int PALE = 3;
    static final int GREEN = 4;
    private static final String[] SHEETS = {"roofs_01", "roofs_02", "roofs_01", "roofs_05", "roofs_05"};
    private static final int[] GROUPS = {0, 0, 32, 32, 0};

    /** The places of a roof group's pieces: south slopes (3 by height), east slopes, hips, inside corners, half slopes, flat top. */
    private static final int SLOPE_S = 0;
    private static final int SLOPE_E = 6;
    private static final int OUTER = 7;
    private static final int INNER = 10;
    private static final int CORNER_SW = 23;
    private static final int CORNER_NE = 30;
    private static final int FLAT = 22;
    /** A square at least this far from a roof's edge is its flat top (a level up, over the slopes' tops). */
    private static final int TOP = 4;
    /** How far past the cell the roofs are looked at, for their heights near its edges to be right. */
    private static final int MARGIN = TOP + 2;

    private final int x0;
    private final int y0;
    private final int side;
    /** Each square's roof level (-1 for none), style and height, around the cell. */
    private final byte[] level;
    private final byte[] style;
    private final byte[] height;

    /** The roofs over a cell's squares (`size` a side from `cellX`, `cellY`) and around them. */
    GielinorRoofs(GielinorMap.GroundGrid ground, GielinorObjects.Placed[] levels, int cellX, int cellY, int size) {
        x0 = cellX - MARGIN;
        y0 = cellY - MARGIN;
        side = size + 2 * MARGIN;
        level = new byte[side * side];
        style = new byte[side * side];
        height = new byte[side * side];
        for (int i = 0; i < side * side; i++) {
            int[] r = roofOver(ground, levels, x0 + i % side, y0 + i / side);
            level[i] = (byte) (r == null ? -1 : r[0]);
            style[i] = (byte) (r == null ? 0 : r[1]);
            height[i] = (byte) (r == null ? 0 : TOP);
        }
        oneStyleEach();
        // How far each square is from its roof's edge, up to the top: a
        // square stands one higher than the lowest of the eight around it.
        for (int pass = 0; pass < TOP; pass++) {
            byte[] next = height.clone();
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) {
                    int i = x + y * side;
                    if (level[i] < 0) {
                        continue;
                    }
                    int lowest = TOP;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx != 0 || dy != 0) {
                                lowest = Math.min(lowest, heightAt(x + dx, y + dy, level[i]));
                            }
                        }
                    }
                    next[i] = (byte) Math.min(height[i], lowest + 1);
                }
            }
            System.arraycopy(next, 0, height, 0, height.length);
        }
    }

    /**
     * Gives each roof (its squares side by side on one level) one style, the
     * one most of it has: the game colours the pieces of a roof separately,
     * and parts of it may only be marked roofed.
     */
    private void oneStyleEach() {
        boolean[] seen = new boolean[side * side];
        int[] queue = new int[side * side];
        for (int start = 0; start < side * side; start++) {
            if (seen[start] || level[start] < 0) {
                continue;
            }
            int head = 0;
            int tail = 0;
            int[] votes = new int[SHEETS.length];
            queue[tail++] = start;
            seen[start] = true;
            while (head < tail) {
                int i = queue[head++];
                votes[style[i]]++;
                int x = i % side;
                int y = i / side;
                int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] s : steps) {
                    int nx = x + s[0];
                    int ny = y + s[1];
                    int n = nx + ny * side;
                    if (nx >= 0 && ny >= 0 && nx < side && ny < side && !seen[n] && level[n] == level[i]) {
                        seen[n] = true;
                        queue[tail++] = n;
                    }
                }
            }
            int best = 0;
            for (int k = 1; k < votes.length; k++) {
                if (votes[k] > votes[best]) {
                    best = k;
                }
            }
            for (int k = 0; k < tail; k++) {
                style[queue[k]] = (byte) best;
            }
        }
    }

    /** A square's height on a roof of a level (by its place around the cell); 0 off the roof, or beyond what's looked at. */
    private int heightAt(int x, int y, int onLevel) {
        if (x < 0 || y < 0 || x >= side || y >= side) {
            return 0;
        }
        int i = x + y * side;
        return level[i] == onLevel ? height[i] : 0;
    }

    /**
     * Adds the roof over a square of the cell (by its world square) to its
     * levels' tiles: a slope or corner on the roof's level, or over the
     * roof's middle a flat top on the level above.
     */
    void addTo(List<List<String>> stack, int zx, int zy) {
        int x = zx - x0;
        int y = zy - y0;
        int i = x + y * side;
        int on = level[i];
        if (on < 0) {
            return;
        }
        String sheet = SHEETS[style[i]];
        int base = GROUPS[style[i]];
        int h = height[i];
        if (h >= TOP) {
            if (on + 1 < stack.size() && stack.get(on + 1).isEmpty()) {
                stack.get(on + 1).add(sheet + "_" + (base + FLAT));
            }
            return;
        }
        boolean lowS = heightAt(x, y + 1, on) < h;
        boolean lowE = heightAt(x + 1, y, on) < h;
        boolean lowN = heightAt(x, y - 1, on) < h;
        boolean lowW = heightAt(x - 1, y, on) < h;
        int piece;
        if (lowS && lowE) {
            piece = OUTER + h;
        } else if (lowS && lowW) {
            piece = CORNER_SW + h;
        } else if (lowE && lowN) {
            piece = CORNER_NE - h;
        } else if (lowS) {
            piece = SLOPE_S + h - 1;
        } else if (lowE) {
            piece = SLOPE_E - h;
        } else if (!lowN && !lowW && heightAt(x + 1, y + 1, on) < h) {
            piece = INNER + h;
        } else {
            // A north or west slope, or a valley facing away: Zomboid draws none.
            return;
        }
        stack.get(on).add(0, sheet + "_" + (base + piece));
    }

    /**
     * The roof over a square: its level and style, or null for none. The
     * game's own roof (its roof objects) on the lowest level above the
     * square's highest floor that has one, but not where that level has a
     * floor (which is the roof then) or a stairwell; else, where the game
     * only marks the highest floor as roofed, a roof over it (slate over
     * stone, clay tiles over wood).
     */
    private static int[] roofOver(GielinorMap.GroundGrid ground, GielinorObjects.Placed[] levels, int zx, int zy) {
        GielinorTerrain.Ground g = ground.at(zx, zy);
        int top = -1;
        boolean roofed = false;
        boolean stone = false;
        if (g != null) {
            top = 0;
            roofed = g != GielinorTerrain.Ground.WATER && ground.indoor(zx, zy);
            stone = g == GielinorTerrain.Ground.STONE;
        }
        for (int z = 1; z < GielinorObjects.LEVELS; z++) {
            int u = ground.upper(z, zx, zy);
            if ((u & GielinorMap.GroundGrid.FLOOR) != 0 || levels[z].isLanding(zx, zy)) {
                top = z;
                roofed = (u & GielinorMap.GroundGrid.ROOFED) != 0;
                stone = (u & GielinorMap.GroundGrid.STONE) != 0;
            }
        }
        for (int z = Math.max(1, top + 1); z < MapCell.LEVELS; z++) {
            int roof = ground.roofAt(z, zx, zy);
            boolean floored = z < GielinorObjects.LEVELS
                && ((ground.upper(z, zx, zy) & GielinorMap.GroundGrid.FLOOR) != 0 || levels[z].isOpen(zx, zy));
            if (roof >= 0 && !floored) {
                return new int[]{z, roof};
            }
        }
        if (top >= 0 && roofed && top + 1 < MapCell.LEVELS && (top + 1 >= GielinorObjects.LEVELS || !levels[top + 1].isOpen(zx, zy))) {
            return new int[]{top + 1, stone ? SLATE : CLAY};
        }
        return null;
    }
}
