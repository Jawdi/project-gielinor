package gielinor.builder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Deterministic roof regressions; no game install or downloaded cache required. */
public final class GielinorRoofsTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture {
        final GielinorMap.GroundGrid ground = new GielinorMap.GroundGrid(3, 1);
        final GielinorObjects.Placed[] levels = GielinorObjects.Placed.levels(ground.width, ground.height);

        void indoor(int x, int y, int floor) {
            ground.setTile(x, y, GielinorTerrain.Ground.STONE);
            ground.setLoaded(x, y, floor == 0);
            if (floor > 0) ground.setUpper(floor, x, y, GielinorMap.GroundGrid.FLOOR | GielinorMap.GroundGrid.ROOFED);
        }

        int zx(int x) { return x * GielinorMap.SCALE; }
        int zy(int y) { return ground.height - (y + 1) * GielinorMap.SCALE; }

        GielinorRoofs roofs(int x, int y, int size) {
            return new GielinorRoofs(ground, levels, x, y, size);
        }

        List<List<String>> at(GielinorRoofs roofs, int x, int y) {
            var stack = new ArrayList<List<String>>();
            for (int z = 0; z < MapCell.LEVELS; z++) stack.add(new ArrayList<>());
            roofs.addTo(stack, x, y);
            return stack;
        }
    }

    @Test
    public void highestStorey() {
        var f = new Fixture();
        for (int x = 20; x < 30; x++) for (int y = 20; y < 30; y++) {
            f.indoor(x, y, 3);
            f.ground.roof(4, x, y, GielinorRoofs.BOARDS);
        }
        var stack = f.at(f.roofs(0, 0, 128), f.zx(25), f.zy(25));
        require(stack.size() > 5 && stack.get(5).contains("roofs_01_54"), "missing flat centre above level 4 slopes");
        require(stack.get(4).contains("roofs_01_54"), "supporting ceiling missing below slopes");
    }

    @Test
    public void steppedRows() {
        steppedRows(false);
    }

    @Test
    public void steppedRowsInReverseOrder() {
        steppedRows(true);
    }

    private static void steppedRows(boolean reverse) {
        var f = new Fixture();
        for (int x = 20; x < 30; x++) for (int y = 20; y < 30; y++) {
            f.indoor(x, y, 0);
            if (x == 20 || x == 29) f.ground.roof((x == 20) != reverse ? 1 : 2, x, y, GielinorRoofs.BOARDS);
        }
        f.ground.fillRoofHoles();
        f.ground.joinRoofs();
        for (int x = 20; x < 30; x++) for (int y = 20; y < 30; y++) {
            require(f.ground.roofAt(1, f.zx(x), f.zy(y)) == GielinorRoofs.BOARDS, "split roof at " + x + "," + y);
            require(f.ground.roofAt(2, f.zx(x), f.zy(y)) == -1, "old stepped roof remains at " + x + "," + y);
        }
        require(f.ground.joinRoofs() == 0, "joining a normalized roof changes it again");
    }

    @Test
    public void inferredRoofsAtEveryStorey() {
        for (int floor = 0; floor < 4; floor++) {
            var f = new Fixture();
            for (int x = 20; x < 30; x++) for (int y = 20; y < 30; y++) f.indoor(x, y, floor);
            var stack = f.at(f.roofs(0, 0, 128), f.zx(25), f.zy(25));
            // Stone ground infers slate; upper floors here have no STONE bit.
            String expected = floor == 0 ? "roofs_01_22" : "roofs_02_22";
            require(stack.size() > floor + 2 && stack.get(floor + 2).contains(expected), "missing inferred cap over floor " + floor);
        }
    }

    @Test
    public void separateBuildingsKeepTheirMaterials() {
        var f = new Fixture();
        for (int x = 20; x < 32; x++) for (int y = 20; y < 26; y++) {
            if (x == 25 || x == 26) continue;
            f.indoor(x, y, 0);
            f.ground.roof(1, x, y, x < 25 ? GielinorRoofs.SLATE : GielinorRoofs.BOARDS);
        }
        f.ground.joinRoofs();
        require(f.ground.roofAt(1, f.zx(24), f.zy(22)) == GielinorRoofs.SLATE, "left building changed material");
        require(f.ground.roofAt(1, f.zx(27), f.zy(22)) == GielinorRoofs.BOARDS, "right building changed material");
        require(f.ground.roofAt(1, f.zx(25), f.zy(22)) < 0, "roof bridges outdoor gap");
    }

    @Test
    public void upperStorey() {
        var f = new Fixture();
        for (int x = 20; x < 28; x++) for (int y = 20; y < 28; y++) {
            int floor = x < 24 ? 0 : 1;
            f.indoor(x, y, floor);
            f.ground.roof(floor + 1, x, y, GielinorRoofs.CLAY);
        }
        f.ground.joinRoofs();
        require(f.ground.roofAt(1, f.zx(21), f.zy(24)) == GielinorRoofs.CLAY, "lower wing raised into upper roof");
        require(f.ground.roofAt(1, f.zx(25), f.zy(24)) < 0, "upper storey flattened into lower roof");
        require(f.ground.roofAt(2, f.zx(25), f.zy(24)) == GielinorRoofs.CLAY, "upper roof lost");
    }

    @Test
    public void atticEavesStayWithTheirRoof() {
        var f = new Fixture();
        for (int x = 20; x < 30; x++) for (int y = 20; y < 30; y++) {
            boolean edge = x == 20 || x == 29 || y == 20 || y == 29;
            f.indoor(x, y, edge ? 0 : 1);
            f.ground.roof(edge ? 1 : 2, x, y, GielinorRoofs.BOARDS);
        }
        f.ground.joinRoofs();
        for (int x = 20; x < 30; x++) for (int y = 20; y < 30; y++) {
            require(f.ground.roofAt(2, f.zx(x), f.zy(y)) == GielinorRoofs.BOARDS, "detached attic eave at " + x + "," + y);
            require(f.ground.roofAt(1, f.zx(x), f.zy(y)) < 0, "old eave level remains");
        }
    }

    @Test
    public void northEastInsideCornerHasVisibleSlope() {
        var f = new Fixture();
        for (int x = 20; x < 30; x++) for (int y = 20; y < 30; y++) {
            if (x >= 25 && y >= 25) continue;
            f.indoor(x, y, 0);
            f.ground.roof(1, x, y, GielinorRoofs.BOARDS);
        }
        var roofs = f.roofs(0, 0, 128);
        for (int step = 0; step < 3; step++) {
            var stack = f.at(roofs, 49 - step, 78 + step);
            require(stack.get(1).contains("roofs_01_" + (37 - step)), "missing north-east valley step " + step);
        }
    }

    @Test
    public void southWestInsideCornerHasVisibleSlope() {
        var f = new Fixture();
        for (int x = 20; x < 30; x++) for (int y = 20; y < 30; y++) {
            if (x < 25 && y < 25) continue;
            f.indoor(x, y, 0);
            f.ground.roof(1, x, y, GielinorRoofs.BOARDS);
        }
        var roofs = f.roofs(0, 0, 128);
        for (int step = 0; step < 3; step++) {
            var stack = f.at(roofs, 50 + step, 77 - step);
            require(stack.get(1).contains("roofs_01_" + (32 + step)), "missing south-west valley step " + step);
        }
    }

    @Test
    public void courtyard() {
        var f = new Fixture();
        for (int x = 20; x < 32; x++) for (int y = 20; y < 32; y++) {
            boolean ring = x < 23 || x >= 29 || y < 23 || y >= 29;
            f.ground.setTile(x, y, GielinorTerrain.Ground.STONE);
            f.ground.setLoaded(x, y, ring);
            if (ring) f.ground.roof(1, x, y, GielinorRoofs.SLATE);
        }
        f.ground.fillRoofHoles();
        f.ground.joinRoofs();
        var centre = f.at(f.roofs(0, 0, 128), f.zx(26), f.zy(26));
        require(centre.stream().allMatch(List::isEmpty), "courtyard covered");
    }

    @Test
    public void stairOpening() {
        var f = new Fixture();
        f.indoor(20, 20, 0);
        f.ground.roof(1, 20, 20, GielinorRoofs.SLATE);
        f.levels[1].open(f.zx(20), f.zy(20));
        var stack = f.at(f.roofs(0, 0, 128), f.zx(20), f.zy(20));
        require(stack.stream().allMatch(List::isEmpty), "roof blocks stair opening");
    }

    @Test
    public void materialBits() {
        var f = new Fixture();
        f.ground.roof(1, 20, 20, GielinorRoofs.CLAY);
        require(f.ground.roofAt(5, f.zx(20), f.zy(20)) == -1, "material bit exposed as a level 5 roof");
    }

    @Test
    public void elevatedRoofClosesAtTheWallTops() {
        var f = new Fixture();
        f.indoor(20, 20, 0);
        f.ground.roof(2, 20, 20, GielinorRoofs.SLATE);
        var stack = f.at(f.roofs(0, 0, 128), f.zx(20), f.zy(20));
        require(stack.get(1).contains("roofs_01_22"), "ground-floor room left open under elevated roof");
    }

    @Test
    public void ladderUpLeavesCeilingAndLandingOpen() {
        var f = new Fixture();
        f.indoor(20, 20, 0);
        f.ground.roof(1, 20, 20, GielinorRoofs.SLATE);
        GielinorArt.reserveLadderOpening(f.levels, 0, f.zx(20), f.zy(20), "up");
        require(f.levels[1].isOpen(f.zx(20), f.zy(20)), "ladder landing not reserved");
        require(f.at(f.roofs(0, 0, 128), f.zx(20), f.zy(20)).stream().allMatch(List::isEmpty), "ladder sealed by ceiling");
    }

    @Test
    public void ladderDownLeavesItsFloorOpen() {
        var f = new Fixture();
        GielinorArt.reserveLadderOpening(f.levels, 1, 40, 80, "down");
        require(f.levels[1].isOpen(40, 80) && f.levels[0].isCeilingOpen(40, 80), "down ladder hole not reserved");
        require(!f.levels[1].isCeilingOpen(40, 80), "down-only ladder removes an unrelated roof above");
    }

    @Test
    public void topStoreyLadderDoesNotCrashOrGetCapped() {
        var f = new Fixture();
        f.indoor(20, 20, 3);
        f.ground.roof(4, 20, 20, GielinorRoofs.SLATE);
        GielinorArt.reserveLadderOpening(f.levels, 3, f.zx(20), f.zy(20), "up");
        require(f.at(f.roofs(0, 0, 128), f.zx(20), f.zy(20)).stream().allMatch(List::isEmpty), "top ladder capped");
    }

    @Test
    public void cellBoundary() {
        var f = new Fixture();
        for (int x = 122; x < 134; x++) for (int y = 20; y < 30; y++) {
            f.indoor(x, y, 0);
            f.ground.roof(1, x, y, GielinorRoofs.BOARDS);
        }
        f.ground.joinRoofs();
        var left = f.roofs(0, 0, 256);
        var right = f.roofs(256, 0, 128);
        var whole = f.roofs(0, 0, 384);
        for (int x = 250; x < 262; x++) for (int y = 70; y < 90; y++) {
            require(f.at(x < 256 ? left : right, x, y).equals(f.at(whole, x, y)), "roof seam at " + x + "," + y);
        }
    }

    @Test
    public void roofedUpperRoomGetsMissingEnclosure() {
        var f = new Fixture();
        f.indoor(20, 20, 1);
        require(GielinorMap.encloseUpperRooms(f.ground, f.levels) == 8, "2 by 2 attic perimeter incomplete");
        require(f.levels[1].style(f.zx(20), f.zy(20), true) != null, "attic side remains open");
    }

    @Test
    public void unroofedUpperFloorRemainsOpen() {
        var f = new Fixture();
        f.ground.setUpper(1, 20, 20, GielinorMap.GroundGrid.FLOOR);
        require(GielinorMap.encloseUpperRooms(f.ground, f.levels) == 0, "outdoor floor enclosed");
    }

    @Test
    public void atticEnclosurePreservesTraversalAndExistingWalls() {
        var f = new Fixture();
        f.indoor(20, 20, 1);
        int x = f.zx(20), y = f.zy(20);
        f.levels[1].open(x, y);
        f.levels[1].edge(x + 2, y, true, GielinorObjects.Style.STONE, GielinorObjects.NONE);
        GielinorMap.encloseUpperRooms(f.ground, f.levels);
        require(f.levels[1].style(x, y, true) == null, "ladder opening enclosed");
        require(f.levels[1].style(x + 1, y, true) == null, "ladder access blocked");
        require(f.levels[1].style(x + 2, y, true) == GielinorObjects.Style.STONE, "existing wall overwritten");
    }

    @Test
    public void serializedCap() throws Exception {
        require(MapCell.LEVELS > 5, "no space for level 5 cap");
        var dir = Files.createTempDirectory("gielinor-roof-test-");
        try {
            var cell = new MapCell(0, 0);
            cell.set(5, 0, 0, "roofs_01_54");
            cell.write(dir);
            var header = ByteBuffer.wrap(Files.readAllBytes(dir.resolve("0_0.lotheader"))).order(ByteOrder.LITTLE_ENDIAN);
            header.position(8);
            int names = header.getInt();
            for (int n = 0; n < names; n++) while (header.get() != '\n') { }
            header.getInt(); header.getInt(); header.getInt();
            require(header.getInt() == 5, "header lost highest cap level");
            var pack = ByteBuffer.wrap(Files.readAllBytes(dir.resolve("world_0_0.lotpack"))).order(ByteOrder.LITTLE_ENDIAN);
            pack.position((int) pack.getLong(12));
            require(pack.getInt() == -1 && pack.getInt() == 5 * MapCell.CHUNK * MapCell.CHUNK, "wrong empty levels before cap");
            require(pack.getInt() == 2 && pack.getInt() == -1 && pack.getInt() == 0, "cap tile missing from lotpack");
        } finally {
            try (var files = Files.list(dir)) {
                for (var file : files.toList()) Files.delete(file);
            }
            Files.delete(dir);
        }
    }
}
