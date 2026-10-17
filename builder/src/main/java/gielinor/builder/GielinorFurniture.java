package gielinor.builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The game's furniture as Zomboid's own: beds, tables, chairs, counters,
 * drawers, shelves, crates, ranges and the rest, by the game's names, each
 * as the Zomboid set nearest it, so its drawers and crates hold loot, its
 * beds are slept in, its chairs sat on and its ranges cook.
 *
 * <p>Zomboid's furniture comes in sets read from its tile definitions: one
 * piece for each way it faces (its front, so a west wall's chair faces
 * east), a piece of several squares being sprites placed by their
 * `SpriteGridPos`. A piece goes in the game's footprint (each of its tiles
 * being two squares by two) with its back against the wall beside it, or,
 * standing free, facing as the game turns it. A table fills its footprint;
 * counters, shelves and pews run along it; the rest stand once.
 *
 * <p>The furniture also names the rooms (see {@link #roomName}), which
 * chooses the loot Zomboid puts in their containers.
 */
final class GielinorFurniture {
    /** Joins the tiles of things stacked on one square (see {@link FurnitureSet#on}). */
    static final String STACK = "+";

    /** How a set fills the game's footprint: once, in a row along its back, or all over. */
    enum Fill {
        ONE, ROW, ALL
    }

    /** A sprite of a piece of furniture and its square in it. */
    record Piece(String tile, int x, int y) {
    }

    /** One of Zomboid's furniture sets: its pieces for each way it faces ('-' for a set that faces no way), in variants. */
    static final class FurnitureSet {
        private final Map<Character, List<List<Piece>>> facings = new HashMap<>();

        /**
         * A set from Zomboid's tile definitions, by its sheet, `CustomName`
         * and `GroupName`. Sprites sharing a facing and a square are
         * variants of the piece.
         */
        static FurnitureSet of(TileDefinitions defs, String sheet, String name, String group) {
            Map<Character, Map<String, List<String>>> found = new TreeMap<>();
            defs.tiles.forEach((tile, props) -> {
                int cut = tile.lastIndexOf('_');
                if (!tile.substring(0, cut).equals(sheet) || !name.equals(props.get("CustomName"))
                        || !group.equals(props.getOrDefault("GroupName", ""))) {
                    return;
                }
                char facing = props.getOrDefault("Facing", "-").charAt(0);
                found.computeIfAbsent(facing, k -> new TreeMap<>())
                    .computeIfAbsent(props.getOrDefault("SpriteGridPos", "0,0"), k -> new ArrayList<>()).add(tile);
            });
            if (found.isEmpty()) {
                throw new IllegalStateException("No " + name + " (" + group + ") in " + sheet);
            }
            FurnitureSet set = new FurnitureSet();
            found.forEach((facing, squares) -> {
                int variants = squares.values().stream().mapToInt(List::size).max().orElse(1);
                List<List<Piece>> out = new ArrayList<>();
                for (int v = 0; v < variants; v++) {
                    List<Piece> piece = new ArrayList<>();
                    for (Map.Entry<String, List<String>> sq : squares.entrySet()) {
                        String[] at = sq.getKey().split(",");
                        List<String> tiles = sq.getValue();
                        piece.add(new Piece(tiles.get(Math.min(v, tiles.size() - 1)), Integer.parseInt(at[0].trim()),
                            Integer.parseInt(at[1].trim())));
                    }
                    out.add(piece);
                }
                set.facings.put(facing, out);
            });
            return set;
        }

        /** A set of single sprites by the way each faces, each with its variants. */
        static FurnitureSet of(Map<Character, String[]> tiles) {
            FurnitureSet set = new FurnitureSet();
            tiles.forEach((facing, variants) -> {
                List<List<Piece>> out = new ArrayList<>();
                for (String t : variants) {
                    out.add(List.of(new Piece(t, 0, 0)));
                }
                set.facings.put(facing, out);
            });
            return set;
        }

        /**
         * The way the set can face nearest `want`: that way, else the
         * opposite (a set made only facing south and east stands back to
         * front facing north and west), else any way for a set that faces
         * none; 0 if it can't. `exact` sets (fixed to a wall) only face `want`.
         */
        char facing(char want, boolean exact) {
            if (facings.containsKey(want)) {
                return want;
            }
            if (exact) {
                return 0;
            }
            if (facings.containsKey(opposite(want))) {
                return opposite(want);
            }
            return facings.containsKey('-') ? '-' : 0;
        }

        /**
         * This set's pieces of one square standing on `base`'s (a sink in a
         * counter), facing the same way: each square's tiles, bottom first,
         * joined by {@link #STACK}.
         */
        FurnitureSet on(FurnitureSet base) {
            FurnitureSet set = new FurnitureSet();
            facings.forEach((facing, variants) -> {
                List<List<Piece>> under = base.facings.get(facing);
                if (under == null) {
                    return;
                }
                List<List<Piece>> out = new ArrayList<>();
                for (List<Piece> piece : variants) {
                    out.add(List.of(new Piece(under.get(0).get(0).tile + STACK + piece.get(0).tile, 0, 0)));
                }
                set.facings.put(facing, out);
            });
            return set;
        }

        List<Piece> piece(char facing, int hash) {
            List<List<Piece>> variants = facings.get(facing);
            return variants.get(Math.floorMod(hash, variants.size()));
        }
    }

    /**
     * A kind of the game's furniture: the set it becomes, how it fills its
     * footprint, whether it's a seat (standing free, it sits at the front
     * of its footprint, by the table it faces), whether it hangs on a wall
     * (only facing away from one), what stands instead where it can't, the
     * room it suggests, with how strongly, and the way the game's model of
     * it faces unturned (see {@link #facing(Kind, int)}).
     */
    record Kind(String name, FurnitureSet set, Fill fill, boolean seat, boolean onWall, Kind instead, String room, int weight,
            char unturned) {
        Kind facingUnturned(char way) {
            return new Kind(name, set, fill, seat, onWall, instead, room, weight, way);
        }
    }

    /** A piece of the game's furniture to place: its level, tile, size in tiles (as turned) and turn. */
    record Furnishing(int level, int x, int y, int width, int height, int turn, Kind kind) {
    }

    /** The rooms furniture suggests, the first winning a tie. */
    private static final List<String> ROOMS = List.of("blacksmith", "church", "tailoringworkshop", "potteryworkshop",
        "carpentryworkshop", "kitchen", "bar", "bedroom", "office", "library", "generalstore", "barn");
    /** A room with no furniture that suggests one. */
    private static final String DEFAULT_ROOM = "livingroom";

    private final Kind table;
    private final Kind smallTable;
    private final Kind chair;
    private final Kind stool;
    private final Kind bench;
    private final Kind pew;
    private final Kind altar;
    private final Kind bed;
    private final Kind doubleBed;
    private final Kind bunkBed;
    private final Kind wardrobe;
    private final Kind drawers;
    private final Kind cupboard;
    private final Kind bookcase;
    private final Kind shelves;
    private final Kind crate;
    private final Kind barrel;
    private final Kind chest;
    private final Kind counter;
    private final Kind bar;
    private final Kind bankCounter;
    private final Kind range;
    private final Kind fireplace;
    private final Kind sink;
    private final Kind anvil;
    private final Kind furnace;
    private final Kind workbench;
    private final Kind coffin;
    private final Kind spinningWheel;
    private final Kind loom;
    private final Kind potteryWheel;
    private final Kind hay;
    private final Kind desk;
    /** The room each piece's first sprite suggests, and how strongly (see {@link Kind}). */
    private final Map<String, Kind> votes = new HashMap<>();
    private final TileDefinitions defs;

    GielinorFurniture(TileDefinitions defs) {
        this.defs = defs;
        table = kind("tables", set("furniture_tables_high_01", "Table", "Large Oak"), Fill.ALL, null, 0);
        smallTable = kind("small tables", set("furniture_tables_high_01", "Oak Round Table", ""), Fill.ONE, null, 0);
        chair = seat("chairs", set("furniture_seating_indoor_02", "Chair", "Wooden"));
        stool = seat("stools", set("crafted_02", "Stool", "Wooden"));
        bench = kind("benches", set("furniture_seating_indoor_02", "Bench", "Long Wooden"), Fill.ROW, null, 0);
        pew = kind("pews", set("location_community_church_small_01", "Pew", "Church"), Fill.ROW, "church", 3);
        altar = kind("altars", set("location_community_church_small_01", "Altar", "Decorated"), Fill.ONE, "church", 5);
        bed = kind("beds", set("furniture_bedding_01", "Bed", "Simple"), Fill.ONE, "bedroom", 4);
        doubleBed = kind("double beds", set("furniture_bedding_01", "Bed", "Large Oak"), Fill.ONE, "bedroom", 4);
        bunkBed = kind("bunk beds", set("furniture_bedding_01", "Beds", "Bunk"), Fill.ONE, "bedroom", 4);
        wardrobe = kind("wardrobes", set("furniture_storage_01", "Wardrobe", "Fancy Oak"), Fill.ONE, "bedroom", 1)
            .facingUnturned('E');
        drawers = kind("drawers", set("furniture_storage_01", "Drawers", "Fancy Oak"), Fill.ONE, "bedroom", 1).facingUnturned('E');
        cupboard = kind("cupboards", set("furniture_storage_01", "Drawers", "Dark Fancy"), Fill.ONE, null, 0).facingUnturned('E');
        bookcase = kind("bookcases", set("furniture_shelving_01", "Shelves", "Oakwood"), Fill.ROW, "library", 2).facingUnturned('E');
        shelves = kind("shelves", set("furniture_shelving_01", "Shelves", "Big Wall"), Fill.ROW, null, 0).facingUnturned('E');
        crate = kind("crates", set("carpentry_01", "Crate", ""), Fill.ONE, "barn", 1);
        barrel = kind("barrels", FurnitureSet.of(Map.of('-', new String[]{"carpentry_02_52"})), Fill.ONE, null, 0);
        // Zomboid's only chest is metal; the game's are wooden, so a crate.
        chest = kind("chests", set("carpentry_02", "Crate", ""), Fill.ONE, null, 0).facingUnturned('N');
        FurnitureSet oakCounter = set("fixtures_counters_01", "Counter", "Oak");
        counter = kind("counters", oakCounter, Fill.ROW, "generalstore", 2);
        bar = kind("bars", set("location_restaurant_bar_01", "Counter", "Oak Bar"), Fill.ROW, "bar", 3);
        bankCounter = kind("bank counters", set("location_business_bank_01", "Counter", "Oak bank"), Fill.ROW, "office", 3);
        range = kind("ranges", set("appliances_cooking_01", "Oven", "Antique"), Fill.ONE, "kitchen", 4);
        // Zomboid's fireplaces stand in a north or west wall; against any other, a stove.
        fireplace = new Kind("fireplaces", FurnitureSet.of(Map.of(
            'S', new String[]{"fixtures_fireplaces_01_0", "fixtures_fireplaces_01_1", "fixtures_fireplaces_01_2"},
            'E', new String[]{"fixtures_fireplaces_01_3", "fixtures_fireplaces_01_4", "fixtures_fireplaces_01_5"})),
            Fill.ONE, false, true, range, null, 0, 'S');
        // Zomboid's sinks sit in a counter.
        sink = kind("sinks", set("fixtures_sinks_01", "Sink", "Beige").on(oakCounter), Fill.ONE, "kitchen", 2);
        anvil = kind("anvils", set("crafted_01", "Anvil", "Blacksmith"), Fill.ONE, "blacksmith", 5);
        furnace = kind("furnaces", set("crafted_01", "Forge", "Primitive"), Fill.ONE, "blacksmith", 5);
        workbench = kind("workbenches", set("crafted_02", "Workbench", ""), Fill.ONE, "carpentryworkshop", 4);
        coffin = kind("coffins", set("crafted_04", "Coffin", "Flat"), Fill.ONE, "church", 2);
        spinningWheel = kind("spinning wheels", set("crafted_04", "Wheel", "Spinning"), Fill.ONE, "tailoringworkshop", 5);
        loom = kind("looms", set("crafted_04", "Loom", "Complex"), Fill.ONE, "tailoringworkshop", 5);
        potteryWheel = kind("pottery wheels", set("crafted_01", "Pottery Wheel", "Simple"), Fill.ONE, "potteryworkshop", 5);
        hay = kind("hay", set("vegetation_farm_01", "Single Stacked Hay", ""), Fill.ONE, null, 0);
        desk = kind("desks", set("furniture_tables_high_01", "Desk", "Lightwood"), Fill.ONE, "office", 3);
    }

    private FurnitureSet set(String sheet, String name, String group) {
        return FurnitureSet.of(defs, sheet, name, group);
    }

    private Kind kind(String name, FurnitureSet set, Fill fill, String room, int weight) {
        Kind k = new Kind(name, set, fill, false, false, null, room, weight, 'S');
        vote(k);
        return k;
    }

    private Kind seat(String name, FurnitureSet set) {
        return new Kind(name, set, Fill.ONE, true, false, null, null, 0, 'S');
    }

    private void vote(Kind k) {
        if (k.room == null) {
            return;
        }
        for (List<List<Piece>> variants : k.set.facings.values()) {
            for (List<Piece> piece : variants) {
                for (Piece p : piece) {
                    if (p.x == 0 && p.y == 0) {
                        votes.put(p.tile, k);
                    }
                }
            }
        }
    }

    /** What a piece of the game's furniture becomes, by its name and footprint in tiles, or null for nothing. */
    Kind kind(String name, int width, int height) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("bank booth") || n.equals("bank table") || n.equals("closed booth")) {
            return bankCounter;
        }
        if (n.equals("bar") || n.equals("bar pumps") || n.equals("beer taps")) {
            return bar;
        }
        if (n.equals("counter") || n.equals("shop counter")) {
            return counter;
        }
        if (n.equals("bunk bed")) {
            return bunkBed;
        }
        if (n.equals("double bed") || n.endsWith("poster bed") || n.equals("royal bed")) {
            return doubleBed;
        }
        if (n.equals("bed")) {
            return width >= 2 && height >= 2 ? doubleBed : bed;
        }
        if (n.equals("wardrobe")) {
            return wardrobe;
        }
        if (n.equals("drawers") || n.equals("dresser") || n.equals("chest of drawers")) {
            return drawers;
        }
        if (n.equals("cabinet") || n.equals("cupboard") || n.equals("bow cabinet")) {
            return cupboard;
        }
        if (n.contains("bookcase") || n.contains("bookshel")) {
            return bookcase;
        }
        if (n.equals("shelves") || n.equals("shelf") || n.endsWith(" shelves")) {
            return shelves;
        }
        if (n.equals("crate") || n.equals("crates") || n.equals("stone crate") || n.equals("boxes") || n.equals("box")
                || n.equals("sacks") || n.equals("sack") || n.equals("sack pile")) {
            return crate;
        }
        if (n.equals("barrel") || n.equals("barrels") || n.equals("keg") || n.equals("cask")) {
            return barrel;
        }
        if (n.equals("chest") || n.equals("closed chest") || n.equals("open chest") || n.equals("trunk")) {
            return chest;
        }
        if (n.equals("range") || n.equals("cooking range") || n.equals("oven") || n.equals("stove")) {
            return range;
        }
        if (n.equals("fireplace")) {
            return fireplace;
        }
        if (n.equals("sink")) {
            return sink;
        }
        if (n.equals("anvil")) {
            return anvil;
        }
        if (n.equals("furnace")) {
            return furnace;
        }
        if (n.equals("workbench") || n.equals("work bench")) {
            return workbench;
        }
        if (n.equals("coffin")) {
            return coffin;
        }
        if (n.equals("spinning wheel")) {
            return spinningWheel;
        }
        if (n.equals("loom")) {
            return loom;
        }
        if (n.equals("potter's wheel")) {
            return potteryWheel;
        }
        if (n.equals("hay bale") || n.equals("hay bales") || n.equals("haystack")) {
            return hay;
        }
        if (n.equals("desk") || n.endsWith(" desk")) {
            return desk;
        }
        if (n.equals("altar")) {
            return altar;
        }
        if (n.endsWith("pew")) {
            return pew;
        }
        if (n.equals("bench")) {
            return bench;
        }
        if ((n.endsWith("chair") || n.equals("throne")) && !n.startsWith("smashed") && !n.startsWith("broken")) {
            return chair;
        }
        if (n.endsWith("stool")) {
            return stool;
        }
        if (n.equals("small table") || n.equals("round table") || n.equals("ivory table") || n.equals("bathroom table")) {
            return smallTable;
        }
        if (n.equals("table") || n.endsWith(" table") && !n.startsWith("bank")) {
            return table;
        }
        return null;
    }

    /**
     * Places the furniture, after everything else (it never stands where
     * something already does, nor over stairs), counting what's placed.
     */
    void place(GielinorObjects.Placed[] levels, GielinorMap.GroundGrid ground, List<Furnishing> all, Map<String, Integer> counts) {
        int scale = GielinorMap.SCALE;
        for (Furnishing f : all) {
            GielinorObjects.Placed placed = levels[f.level];
            int x0 = (f.x - GielinorMap.FIRST_SQUARE_X * 64) * scale;
            // The footprint's northern row (the game's tiles run north as y grows).
            int y0 = placed.height - (f.y + f.height - GielinorMap.FIRST_SQUARE_Y * 64) * scale;
            int fw = f.width * scale;
            int fh = f.height * scale;
            char turned = facing(f.kind, f.turn);
            char wall = wallSide(placed, x0, y0, fw, fh, opposite(turned));
            Kind kind = f.kind;
            // Its back to the wall beside it; standing free, as the game turns
            // it, or, filling its footprint, along its length.
            char facing = wall != 0 ? opposite(wall) : kind.fill == Fill.ALL && fw != fh ? (fw > fh ? 'S' : 'E') : turned;
            char sprites = kind.set.facing(facing, kind.onWall);
            if (sprites == 0 && kind.instead != null) {
                kind = kind.instead;
                sprites = kind.set.facing(facing, kind.onWall);
            }
            if (sprites == 0) {
                continue;
            }
            int hash = GielinorMap.scramble(x0, y0 + f.level * 7919);
            List<Piece> piece = kind.set.piece(sprites, hash);
            int pw = 1;
            int ph = 1;
            for (Piece p : piece) {
                pw = Math.max(pw, p.x + 1);
                ph = Math.max(ph, p.y + 1);
            }
            // The side of the footprint it stands on: its back, or for a seat
            // standing free its front, by the table it faces.
            char side = wall == 0 && kind.seat ? facing : opposite(facing);
            boolean alongX = facing == 'N' || facing == 'S';
            List<int[]> origins = new ArrayList<>();
            if (kind.fill == Fill.ALL) {
                for (int oy = y0; oy == y0 || oy + ph <= y0 + fh; oy += ph) {
                    for (int ox = x0; ox == x0 || ox + pw <= x0 + fw; ox += pw) {
                        origins.add(new int[]{ox, oy});
                    }
                }
            } else {
                int depth = alongX ? (side == 'N' ? y0 : y0 + fh - ph) : (side == 'W' ? x0 : x0 + fw - pw);
                int length = alongX ? fw : fh;
                int size = alongX ? pw : ph;
                int start = alongX ? x0 : y0;
                if (kind.fill == Fill.ONE) {
                    int at = start + Math.max(0, (length - size) / 2);
                    origins.add(alongX ? new int[]{at, depth} : new int[]{depth, at});
                } else {
                    for (int at = start; at == start || at + size <= start + length; at += size) {
                        origins.add(alongX ? new int[]{at, depth} : new int[]{depth, at});
                    }
                }
            }
            int placedPieces = 0;
            for (int[] o : origins) {
                if (free(placed, ground, f.level, o, piece)) {
                    for (Piece p : piece) {
                        placed.stand(o[0] + p.x, o[1] + p.y, p.tile);
                    }
                    placedPieces++;
                }
            }
            if (placedPieces > 0) {
                counts.merge(kind.name, placedPieces, Integer::sum);
            }
        }
    }

    /**
     * Which way a piece standing free faces (its front), turned as the game
     * turns it: each turn a quarter clockwise from the way its model faces
     * unturned. That differs by model, so was measured: a chair unturned
     * faces south (to the table beside it), shelves, wardrobes and drawers
     * east (their backs to the wall west of them) and chests north.
     */
    private static char facing(Kind kind, int turn) {
        String clockwise = "NESW";
        return clockwise.charAt((clockwise.indexOf(kind.unturned) + turn) % 4);
    }

    /** Whether every square of a piece is free: inside the world, nothing standing there, no stairs, and dry ground under it. */
    private static boolean free(GielinorObjects.Placed placed, GielinorMap.GroundGrid ground, int level, int[] origin, List<Piece> piece) {
        for (Piece p : piece) {
            int x = origin[0] + p.x;
            int y = origin[1] + p.y;
            if (!placed.inside(x, y) || placed.standing(x, y) != null || placed.isOpen(x, y) || placed.isLanding(x, y)) {
                return false;
            }
            if (level == 0 && (ground.at(x, y) == null || ground.at(x, y) == GielinorTerrain.Ground.WATER)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The side of a footprint (in squares) a wall runs along ('N', 'E', 'S'
     * or 'W'), the one with the most wall, `preferred` winning a tie; 0 for
     * none.
     */
    private static char wallSide(GielinorObjects.Placed placed, int x0, int y0, int fw, int fh, char preferred) {
        int[] walls = new int[4];
        for (int x = x0; x < x0 + fw; x++) {
            walls[0] += placed.style(x, y0, false) != null ? 1 : 0;
            walls[2] += placed.style(x, y0 + fh, false) != null ? 1 : 0;
        }
        for (int y = y0; y < y0 + fh; y++) {
            walls[3] += placed.style(x0, y, true) != null ? 1 : 0;
            walls[1] += placed.style(x0 + fw, y, true) != null ? 1 : 0;
        }
        String sides = "NESW";
        char best = 0;
        int most = 0;
        for (int i = 0; i < 4; i++) {
            if (walls[i] > most || walls[i] == most && most > 0 && sides.charAt(i) == preferred) {
                most = walls[i];
                best = sides.charAt(i);
            }
        }
        return best;
    }

    private static char opposite(char facing) {
        return switch (facing) {
            case 'N' -> 'S';
            case 'S' -> 'N';
            case 'E' -> 'W';
            case 'W' -> 'E';
            default -> facing;
        };
    }

    /**
     * A container for a room that has too few, against a wall, its front
     * `facing` away from it: what the room's kind keeps things in (a
     * kitchen's counters, a bedroom's drawers, a library's bookcases, a
     * shop's shelves, a workshop's crates), else cupboards, shelves or
     * crates. Null if there's none that faces so.
     */
    String container(String room, char facing, int hash) {
        Kind kind = switch (room) {
            case "kitchen" -> counter;
            case "bedroom" -> drawers;
            case "library" -> bookcase;
            case "generalstore", "office", "bar" -> shelves;
            case "blacksmith", "barn", "carpentryworkshop", "potteryworkshop", "tailoringworkshop", "church" -> crate;
            default -> new Kind[]{cupboard, shelves, crate}[Math.floorMod(hash, 3)];
        };
        char way = kind.set.facing(facing, false);
        return way == 0 ? null : kind.set.piece(way, hash).get(0).tile;
    }

    /**
     * A room's name, by what its furniture suggests (see {@link Kind}): the
     * room with the most weight, else {@link #DEFAULT_ROOM}. `tiles` are the
     * things standing in the room.
     */
    String roomName(Iterable<String> tiles) {
        Map<String, Integer> weight = new HashMap<>();
        for (String stacked : tiles) {
            for (String t : stacked == null ? new String[0] : stacked.split(java.util.regex.Pattern.quote(STACK))) {
                Kind k = votes.get(t);
                if (k != null) {
                    weight.merge(k.room, k.weight, Integer::sum);
                }
            }
        }
        String best = DEFAULT_ROOM;
        int most = 0;
        for (String room : ROOMS) {
            int w = weight.getOrDefault(room, 0);
            if (w > most) {
                most = w;
                best = room;
            }
        }
        return best;
    }
}
