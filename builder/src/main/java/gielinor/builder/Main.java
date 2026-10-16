package gielinor.builder;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.runelite.cache.ItemManager;
import net.runelite.cache.NpcManager;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.fs.Store;

/**
 * Project Gielinor's content builder. Reads one pinned version of the Old
 * School RuneScape game cache (fetched once from the OpenRS2 archive; see
 * {@link Pinned}) and writes the content pack the Project Gielinor mod needs
 * into Zomboid's mods folder: items, models, zombies and the world. Nothing
 * from the game is shipped with the mod; this builds it on your machine.
 * `--cache` builds from another cache folder instead.
 *
 * <pre>
 * gielinor-content-builder [--cache DIR] [--zomboid DIR] [--out DIR]
 * </pre>
 */
public final class Main {
    /** The content pack's mod id, which Project Gielinor requires. */
    static final String PACK_ID = "ProjectGielinorContent";
    /** The script module the items go in: `Gielinor.BronzeAxe`. */
    static final String MODULE = "Gielinor";

    private record Built(ItemDefinition item, String type, ItemConverter.Role role, String kind, Map<String, String> properties,
            List<ZomboidItems.Block> components) {
    }

    public static void main(String[] args) throws Exception {
        File cache = null;
        File zomboid = null;
        File out = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--cache" -> cache = new File(args[++i]);
                case "--zomboid" -> zomboid = new File(args[++i]);
                case "--out" -> out = new File(args[++i]);
                default -> {
                    System.err.println("unknown option " + args[i]);
                    System.err.println("usage: gielinor-content-builder [--cache DIR] [--zomboid DIR] [--out DIR]");
                    System.exit(2);
                }
            }
        }
        if (cache == null) {
            cache = Pinned.cache();
        }
        if (!Locations.isCache(cache)) {
            throw new IllegalStateException(cache + " isn't a game cache (no main_file_cache.dat2)");
        }
        if (zomboid == null) {
            zomboid = Locations.findZomboid().orElseThrow(() -> new IllegalStateException(
                "Project Zomboid wasn't found. Pass --zomboid <install folder>."));
        }
        if (out == null) {
            out = new File(Locations.modsFolder(), PACK_ID);
        }
        System.out.println("cache:   " + cache);
        System.out.println("zomboid: " + zomboid);
        System.out.println("output:  " + out);

        ZomboidItems.Scripts scripts = ZomboidItems.load(zomboid);
        try (Store store = new Store(cache)) {
            store.load();
            ItemManager items = new ItemManager(store);
            items.load();
            IconRenderer icons = new IconRenderer(store, items);
            ItemModels itemModels = new ItemModels(new ModelExporter(store), scripts, zomboid);
            List<Built> built = build(items, scripts.items(), out.toPath().resolve("item-report.txt"));
            NpcManager npcs = new NpcManager(store);
            npcs.load();
            writePack(out.toPath(), built, icons, itemModels, npcs, scripts, zomboid);
            // Zomboid finds a mod's maps in its common folder (and only looks in
            // the version folder if the common one has maps too).
            GielinorMap.write(out.toPath().resolve("common/media"), TileDefinitions.read(zomboid.toPath()), new GielinorTerrain(store));
        }
    }

    /**
     * Every item the mod has a use for (see {@link ItemFilter}), once per
     * name, lowest id first. Writes what was left out, and why, to
     * `report`.
     */
    private static List<Built> build(ItemManager items, Map<String, ZomboidItems.Block> templates, Path report) throws IOException {
        List<ItemDefinition> all = new ArrayList<>(items.getItems());
        all.sort(Comparator.comparingInt(d -> d.id));
        ItemFilter filter = new ItemFilter(all);
        Set<String> names = new HashSet<>();
        List<Built> out = new ArrayList<>();
        Map<String, List<String>> dropped = new TreeMap<>();
        for (ItemDefinition d : all) {
            boolean variant = d.notedTemplate != -1 || d.placeholderTemplateId != -1 || d.boughtTemplateId != -1;
            if (variant || d.name == null || d.name.isBlank() || d.name.equals("null") || d.inventoryModel <= 0) {
                continue;
            }
            if (!names.add(d.name.toLowerCase())) {
                continue;
            }
            ItemConverter.Role role = ItemConverter.roleOf(d);
            String kind = ItemFilter.kindOf(d, role);
            String reason = filter.reasonToDrop(d, kind);
            if (reason != null) {
                dropped.computeIfAbsent(reason, r -> new ArrayList<>()).add(d.name);
                continue;
            }
            String type = ItemConverter.typeName(d.name);
            Map<String, String> props = ItemConverter.properties(d, type, role, templates);
            if (props != null) {
                out.add(new Built(d, type, role, kind, props, ItemConverter.components(d, role, templates)));
            }
        }
        StringBuilder text = new StringBuilder("Items left out of the content pack, by reason. Keep or drop any by name in\n"
            + "builder/src/main/resources/item-overrides.txt.\n");
        dropped.forEach((reason, list) -> {
            System.out.println("  left out, " + reason + ": " + list.size());
            text.append("\n").append(reason).append(" (").append(list.size()).append(")\n");
            list.forEach(n -> text.append("  ").append(n).append("\n"));
        });
        Files.createDirectories(report.getParent());
        Files.writeString(report, text, StandardCharsets.UTF_8);
        return out;
    }

    /**
     * Humanoid monsters that walk among the undead: fitted to a human's size
     * like the zombies, and mixed in less often. Only human-shaped ones: giants
     * and ogres are built too broad to move on a human skeleton.
     */
    private static final Set<String> MOBS = Set.of(
        "goblin", "hobgoblin", "skeleton", "guard", "barbarian", "dark wizard", "thug", "mugger",
        "highwayman", "pirate", "monk of zamorak", "black knight", "white knight", "warrior woman",
        "al-kharid warrior", "ghoul", "werewolf");
    /** How many looks of each monster to take (many have dozens of near-identical recolours). */
    private static final int LOOKS_PER_MOB = 3;

    /**
     * Every distinct zombie in the game, and the humanoid monsters in
     * {@link #MOBS}, as full-body clothing pieces, each its own outfit
     * (`GielinorZombie_<npc id>` and `GielinorMob_<npc id>`) for the mod to put
     * among Zomboid's zombies. Returns the outfits' `clothing.xml`, and lists
     * their names in `lua/shared/Gielinor_Outfits.lua` so the mod knows them
     * before Zomboid has read its outfits.
     */
    private static String undead(NpcManager npcs, ItemModels itemModels, Path media) throws IOException {
        List<NpcDefinition> all = new ArrayList<>(npcs.getNpcs());
        all.sort(Comparator.comparingInt(n -> n.id));
        Set<String> seen = new HashSet<>();
        Map<String, Integer> perMob = new java.util.HashMap<>();
        StringBuilder male = new StringBuilder();
        StringBuilder female = new StringBuilder();
        List<String> zombieNames = new ArrayList<>();
        List<String> mobNames = new ArrayList<>();
        for (NpcDefinition n : all) {
            if (n.name == null || n.models == null || n.models.length == 0) {
                continue;
            }
            String lower = n.name.toLowerCase();
            boolean zombie = lower.contains("zombie");
            if (!zombie && (!MOBS.contains(lower) || perMob.getOrDefault(lower, 0) >= LOOKS_PER_MOB)) {
                continue;
            }
            String look = java.util.Arrays.toString(n.models) + java.util.Arrays.toString(n.recolorToReplace);
            if (!seen.add(look)) {
                continue;
            }
            String kind = zombie ? "Zombie" : "Mob";
            String name = "Gielinor_" + kind + "_" + n.id;
            if (!itemModels.exportFigure(name, n.models, n.recolorToFind, n.recolorToReplace, true, media)) {
                continue;
            }
            String guid = itemModels.guidOf(name);
            String outfitGuid = java.util.UUID.nameUUIDFromBytes(("ProjectGielinor:outfit:" + name).getBytes(StandardCharsets.UTF_8)).toString();
            String outfit = "\t\t<m_Name>Gielinor" + kind + "_" + n.id + "</m_Name>\r\n\t\t<m_Guid>" + outfitGuid + "</m_Guid>\r\n"
                + "\t\t<m_Top>false</m_Top>\r\n\t\t<m_Pants>false</m_Pants>\r\n"
                + "\t\t<m_items>\r\n\t\t\t<itemGUID>" + guid + "</itemGUID>\r\n\t\t</m_items>\r\n";
            male.append("\t<m_MaleOutfits>\r\n").append(outfit).append("\t</m_MaleOutfits>\r\n");
            female.append("\t<m_FemaleOutfits>\r\n").append(outfit).append("\t</m_FemaleOutfits>\r\n");
            if (zombie) {
                zombieNames.add("Gielinor" + kind + "_" + n.id);
            } else {
                perMob.merge(lower, 1, Integer::sum);
                mobNames.add("Gielinor" + kind + "_" + n.id);
            }
        }
        System.out.println("  zombie looks: " + zombieNames.size() + ", monster looks: " + mobNames.size());
        Path lua = media.resolve("lua/shared/Gielinor_Outfits.lua");
        Files.createDirectories(lua.getParent());
        Files.writeString(lua, "-- Written by the Project Gielinor Content Builder: the content pack's zombie\n"
            + "-- and monster outfits.\n\nGielinorOutfits = {\n    zombies = " + luaList(zombieNames)
            + ",\n    monsters = " + luaList(mobNames) + ",\n}\n", StandardCharsets.UTF_8);
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n<outfitManager>\r\n" + female + male + "</outfitManager>\r\n";
    }

    private static String luaList(List<String> names) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < names.size(); i++) {
            out.append(i % 6 == 0 ? "\n        " : " ").append('"').append(names.get(i)).append("\",");
        }
        return out.append("\n    }").toString();
    }

    /**
     * Zomboid's animals and the game's creatures that become them, by
     * Zomboid animal type and NPC id: cows, bulls and calves, chickens and
     * roosters, sheep, rams and lambs, pigs, boars and piglets, rabbits, rats,
     * mice, raccoons and the stag for the buck. Types with no counterpart
     * (turkeys, chicks, does, fawns, rabbit kittens) stay Zomboid's.
     */
    private static final String[][] ANIMALS = {
        {"cow", "2790"}, {"bull", "15625"}, {"cowcalf", "2792"},
        {"hen", "1173"}, {"cockerel", "1175"},
        {"ewe", "731"}, {"ram", "1261"}, {"lamb", "1177"},
        {"sow", "2796"}, {"boar", "11066"}, {"piglet", "2798"},
        {"rabbuck", "1852"}, {"rabdoe", "1852"},
        {"rat", "1020"}, {"ratfemale", "1020"}, {"ratbaby", "1020"},
        {"mouse", "4000"}, {"mousefemale", "4000"}, {"mousepups", "4000"},
        {"raccoonboar", "1419"}, {"raccoonsow", "1419"}, {"raccoonkit", "1419"},
        {"buck", "1845"},
    };

    /**
     * Each mapped animal type as a model `GielinorAnimal_<type>` in the Base
     * module (copying the vanilla body model's shader, animations and
     * attachments, with the creature's mesh) and a body texture of the same
     * name. The mod points the animal's definition at them. Returns the
     * models' script.
     */
    private static String animals(NpcManager npcs, ItemModels itemModels, ZomboidItems.Scripts scripts, File zomboid, Path media) throws IOException {
        // Which model each animal type is drawn with, from Zomboid's animal definitions.
        Map<String, String> bodyModels = new java.util.HashMap<>();
        java.util.regex.Pattern line = java.util.regex.Pattern.compile("AnimalDefinitions\\.animals\\[\"([^\"]+)\"\\]\\.bodyModel\\s*=\\s*\"([^\"]+)\"");
        File[] definitions = new File(zomboid, "media/lua/shared/Definitions/animal").listFiles((d, n) -> n.endsWith(".lua"));
        for (File f : definitions == null ? new File[0] : definitions) {
            java.util.regex.Matcher m = line.matcher(Files.readString(f.toPath(), StandardCharsets.ISO_8859_1));
            while (m.find()) {
                bodyModels.put(m.group(1), m.group(2));
            }
        }
        StringBuilder script = new StringBuilder("module Base\n{\n");
        int count = 0;
        for (String[] animal : ANIMALS) {
            String type = animal[0];
            NpcDefinition npc = npcs.get(Integer.parseInt(animal[1]));
            ZomboidItems.Block model = scripts.models().get(bodyModels.get(type));
            if (npc == null || npc.models == null || model == null || model.properties().get("mesh") == null) {
                continue;
            }
            String mesh = model.properties().get("mesh");
            File dir = new File(zomboid, "media/models_X/" + mesh).getParentFile();
            String base = new File(mesh).getName();
            File[] found = dir.listFiles((d, n) -> n.equalsIgnoreCase(base + ".x"));
            if (found == null || found.length == 0) {
                continue;
            }
            String name = "GielinorAnimal_" + type;
            if (!itemModels.exportAnimal(name, npc.models, npc.recolorToFind, npc.recolorToReplace, found[0].toPath(), media)) {
                continue;
            }
            Map<String, String> props = new java.util.LinkedHashMap<>(model.properties());
            props.put("mesh", "Skinned/Gielinor/" + name);
            script.append('\n').append(new ZomboidItems.Block("model " + name, props, model.children()).text(4));
            count++;
        }
        System.out.println("  animals: " + count);
        return script.append("}\n").toString();
    }

    /**
     * A part of a Gielinor body as the game's character designer offers it:
     * its name (and slot, `gielinor:<name>`), the game's number for it on
     * body type A (body type B's is 7 more), which of the player's colours
     * it takes (null for none), and the parts of Zomboid's body it hides.
     * Separate parts let clothing replace them as the game's equipment does:
     * a platebody hides the torso and arms, platelegs the legs.
     */
    private record BodyPart(String name, int part, String colour, List<Integer> masks) {
    }

    private static final List<BodyPart> BODY_PARTS = List.of(
        new BodyPart("hair", 0, "hair", List.of(0)),
        new BodyPart("jaw", 1, "hair", List.of()),
        new BodyPart("torso", 2, "torso", List.of(1)),
        new BodyPart("arms", 3, "torso", List.of(3, 5)),
        new BodyPart("hands", 4, null, List.of(4, 6)),
        new BodyPart("legs", 5, "legs", List.of(2, 7, 9)),
        new BodyPart("feet", 6, "feet", List.of(8, 10)));

    /**
     * One of the colours a player picks, as the game's client recolours its
     * body models: the colour (packed HSL) the kits are modelled in and what
     * each choice turns it into, and the same for a second shade some kits
     * use alongside it (-1 and none where no kit does). The designer offers
     * the first `offered` choices; the rest are unlocked in the game.
     */
    private record BodyColour(String name, int source, short[] choices, int secondSource, short[] secondChoices, int offered) {
    }

    private static final List<BodyColour> BODY_COLOURS = List.of(
        new BodyColour("hair", 6798,
            new short[]{6798, 107, 10283, 16, 4797, 7744, 5799, 4634, -31839, 22433, 2983, -11343, 8, 5281, 10438, 3650, -27322, -21845, 200, 571, 908, 21830, 28946, -15701, -14010},
            -10304 & 0xffff,
            new short[]{6554, 115, 10304, 28, 5702, 7756, 5681, 4510, -31835, 22437, 2859, -11339, 16, 5157, 10446, 3658, -27314, -21965, 472, 580, 784, 21966, 28950, -15697, -14002},
            25),
        new BodyColour("torso", 8741,
            new short[]{8741, 12, -1506, -22374, 7735, 8404, 1701, -27106, 24094, 10153, -8915, 4783, 1341, 16578, -30533, 25239, 8, 5281, 10438, 3650, -27322, -21845, 200, 571, 908, 21830, 28946, -15701, -14010},
            9104,
            new short[]{9104, 10275, 7595, 3610, 7975, 8526, 918, -26734, 24466, 10145, -6882, 5027, 1457, 16565, -30545, 25486, 24, 5392, 10429, 3673, -27335, -21957, 192, 687, 412, 21821, 28835, -15460, -14019},
            29),
        new BodyColour("legs", 25238,
            new short[]{25238, 8742, 12, -1506, -22374, 7735, 8404, 1701, -27106, 24094, 10153, -8915, 4783, 1341, 16578, -30533, 8, 5281, 10438, 3650, -27322, -21845, 200, 571, 908, 21830, 28946, -15701, -14010},
            -1, new short[0], 29),
        new BodyColour("feet", 4626, new short[]{4626, 11146, 6439, 12, 4758, 10270}, -1, new short[0], 6),
        new BodyColour("skin", ItemModels.SKIN_SOURCE,
            new short[]{4550, 4537, 5681, 5673, 5790, 6806, 8076, 4574, 17050, 0, 127, -31821, -17991},
            -1, new short[0], 8));

    private static BodyColour bodyColour(String name) {
        return BODY_COLOURS.stream().filter(c -> c.name.equals(name)).findFirst().orElse(null);
    }

    /** Each body type's default look, by kit, in {@link #BODY_PARTS}' order (-1: none). */
    private static final int[][] DEFAULT_KITS = {
        {0, 10, 18, 26, 33, 36, 42},
        {45, -1, 56, 61, 67, 70, 79},
    };

    /**
     * Every body part a player can choose (each of the game's selectable
     * identity kits) as clothing items `GielinorKit<id>` plus each piece's
     * suffix (see {@link ItemModels#exportKit}), and a list of them for the
     * mod's character designer (`lua/shared/Gielinor_Kits.lua`). Returns
     * their item script.
     */
    private static String bodies(ItemModels itemModels, Path media) throws IOException {
        StringBuilder script = new StringBuilder("module " + MODULE + "\n{\n    imports\n    {\n        Base\n    }\n");
        StringBuilder lua = new StringBuilder("-- Written by the Project Gielinor Content Builder: the body parts a\n"
            + "-- character can be made of, by body type (A and B) and part, each as the\n"
            + "-- items of its pieces (fixed colours, skin, and the player's chosen\n"
            + "-- colour), and each body type's default look.\n\nGielinorKits = {\n");
        double[] crowns = new double[2];
        for (int type = 0; type < 2; type++) {
            List<Integer> defaults = new ArrayList<>();
            for (int kit : DEFAULT_KITS[type]) {
                if (kit >= 0) {
                    defaults.add(kit);
                }
            }
            crowns[type] = itemModels.kitCrown(defaults);
        }
        java.util.Map<String, StringBuilder> parts = new java.util.LinkedHashMap<>();
        int count = 0;
        for (net.runelite.cache.definitions.KitDefinition kit : itemModels.selectableKits()) {
            if (kit.bodyPartId < 0 || kit.bodyPartId >= 14) {
                continue;
            }
            int type = kit.bodyPartId / 7;
            BodyPart part = BODY_PARTS.get(kit.bodyPartId % 7);
            BodyColour colour = part.colour == null ? null : bodyColour(part.colour);
            String name = "Gielinor_Kit" + kit.getId();
            java.util.Set<ItemModels.Piece> pieces = itemModels.exportKit(name, kit.getId(), colour == null ? -1 : colour.source,
                colour == null ? -1 : colour.secondSource, crowns[type], part.masks, media);
            if (pieces.isEmpty()) {
                continue;
            }
            StringBuilder entry = new StringBuilder("            { kit = " + kit.getId());
            for (ItemModels.Piece piece : pieces) {
                String item = "GielinorKit" + kit.getId() + piece.suffix;
                String slot = "gielinor:" + part.name + (piece == ItemModels.Piece.FIXED ? "" : "_" + piece.key);
                script.append("\n    item ").append(item).append("\n    {\n")
                    .append("        DisplayCategory = Clothing,\n")
                    .append("        ItemType = base:clothing,\n")
                    .append("        DisplayName = Gielinor ").append(part.name).append(",\n")
                    .append("        Icon = TshirtGeneric,\n")
                    .append("        Weight = 0.01,\n")
                    .append("        BodyLocation = ").append(slot).append(",\n")
                    .append("        ClothingItem = ").append(name).append(piece.suffix).append(",\n")
                    .append("    }\n");
                entry.append(", ").append(piece.key).append(" = \"").append(MODULE).append('.').append(item).append('"');
            }
            parts.computeIfAbsent((type == 0 ? "A" : "B") + "." + part.name, k -> new StringBuilder())
                .append(entry).append(" },\n");
            count++;
        }
        for (String type : new String[]{"A", "B"}) {
            lua.append("    ").append(type).append(" = {\n");
            for (BodyPart part : BODY_PARTS) {
                StringBuilder entries = parts.get(type + "." + part.name);
                lua.append("        ").append(part.name).append(" = {\n").append(entries == null ? "" : entries).append("        },\n");
            }
            lua.append("    },\n");
        }
        lua.append("    defaults = {\n");
        for (int type = 0; type < 2; type++) {
            lua.append("        ").append(type == 0 ? "A" : "B").append(" = {");
            for (int p = 0; p < BODY_PARTS.size(); p++) {
                if (DEFAULT_KITS[type][p] >= 0) {
                    lua.append(' ').append(BODY_PARTS.get(p).name).append(" = ").append(DEFAULT_KITS[type][p]).append(',');
                }
            }
            lua.append(" },\n");
        }
        lua.append("    },\n");
        // The colours as the game's client shows them: each choice's colour
        // and its second shade (the first again where there's none).
        lua.append("    colours = {\n");
        for (BodyColour colour : BODY_COLOURS) {
            lua.append("        ").append(colour.name).append(" = {\n");
            for (int i = 0; i < colour.offered; i++) {
                int first = ModelExporter.rgb(colour.choices[i] & 0xffff);
                int second = i < colour.secondChoices.length ? ModelExporter.rgb(colour.secondChoices[i] & 0xffff) : first;
                lua.append(String.format("            {0x%06x, 0x%06x},", first, second)).append('\n');
            }
            lua.append("        },\n");
        }
        lua.append("    },\n}\n");
        Path file = media.resolve("lua/shared/Gielinor_Kits.lua");
        Files.createDirectories(file.getParent());
        Files.writeString(file, lua, StandardCharsets.UTF_8);
        System.out.println("  body parts: " + count);
        return script.append("}\n").toString();
    }

    /**
     * The loot list (`lua/shared/Gielinor_Loot.lua`): every tradeable item's
     * type, kind and value in coins, for the mod to place in Zomboid's loot
     * tables. Untradeable items are mostly quest and minigame pieces, which
     * don't belong in a house's cupboards.
     */
    private static String lootList(List<Built> kept) {
        StringBuilder out = new StringBuilder("-- Written by the Project Gielinor Content Builder: tradeable items as\n"
            + "-- {type, kind, value in coins}, for the mod's loot rules.\n\nGielinorLoot = {\n");
        int count = 0;
        for (Built b : kept) {
            if (!b.item.tradeable) {
                continue;
            }
            out.append("    {\"").append(b.type).append("\", \"").append(b.kind).append("\", ")
                .append(Math.max(b.item.cost, 1)).append("},\n");
            count++;
        }
        System.out.println("  loot list: " + count + " items");
        return out.append("}\n").toString();
    }

    /** Writes the pack in Build 42's layout: `42/mod.info`, `42/media`, and a `common` folder. */
    private static void writePack(Path out, List<Built> built, IconRenderer icons, ItemModels itemModels, NpcManager npcs,
            ZomboidItems.Scripts scripts, File zomboid) throws IOException {
        Path b42 = out.resolve("42");
        Path media = b42.resolve("media");
        deleteTree(media);
        Files.createDirectories(media.resolve("scripts"));
        Files.createDirectories(media.resolve("textures"));
        Files.createDirectories(media.resolve("lua/shared/Translate/EN"));
        Files.createDirectories(out.resolve("common"));
        Files.writeString(b42.resolve("mod.info"), String.join("\n",
            "name=Project Gielinor Content (built locally)",
            "id=" + PACK_ID,
            "description=Built on this machine by the Project Gielinor content builder from your own game install. Not for sharing.",
            "modversion=0.1",
            "versionMin=42.0.0",
            ""));

        // Icons first: items whose icon won't render are left out. Weapons
        // get their own held models, everything else its ground model.
        StringBuilder models = new StringBuilder("module " + MODULE + "\n{\n    imports\n    {\n        Base\n    }\n");
        int modelCount = 0;
        int groundCount = 0;
        int wornCount = 0;
        List<Built> kept = new ArrayList<>();
        Set<String> types = new HashSet<>();
        for (Built b : built) {
            // Unique ignoring case: Windows file names don't tell case apart.
            String type = b.type;
            if (!types.add(type.toLowerCase())) {
                type = type + "_" + b.item.id;
                types.add(type.toLowerCase());
            }
            BufferedImage icon = icons.icon(b.item.id);
            if (icon == null) {
                continue;
            }
            ImageIO.write(icon, "png", media.resolve("textures/Item_Gielinor_" + type + ".png").toFile());
            Map<String, String> props = b.properties;
            props.put("Icon", "Gielinor_" + type);
            // Models are looked up in Base unless the name says which module.
            if (b.role.isWeapon()) {
                String model = itemModels.exportHeld(b.item, type, b.role, media);
                if (model != null) {
                    props.remove("WeaponSprite");
                    props.put("WeaponSprite", MODULE + "." + type);
                    models.append('\n').append(model);
                    modelCount++;
                }
            } else {
                boolean worn = false;
                if (b.role == ItemConverter.Role.WEAR) {
                    worn = itemModels.exportWorn(b.item, type, ItemConverter.Slot.of(b.item.wearPos1), media);
                    if (worn) {
                        props.put("ClothingItem", "Gielinor_" + type);
                        wornCount++;
                    } else {
                        // Nothing to wear after all: carry it instead.
                        for (String k : new String[]{"BodyLocation", "ClothingItem", "BloodLocation", "BiteDefense", "ScratchDefense"}) {
                            props.remove(k);
                        }
                        props.put("ItemType", "base:normal");
                        props.put("DisplayCategory", "Material");
                    }
                }
                String ground = type + "_Ground";
                String model = itemModels.exportGround(b.item, ground, media);
                if (model != null) {
                    // Clothing is worn, not held, so it only needs its ground model.
                    if (!worn) {
                        props.put("StaticModel", MODULE + "." + ground);
                    }
                    props.put("WorldStaticModel", MODULE + "." + ground);
                    models.append('\n').append(model);
                    groundCount++;
                }
            }
            kept.add(new Built(b.item, type, b.role, b.kind, props, b.components));
        }
        models.append("}\n");
        Files.writeString(media.resolve("scripts/gielinor_models.txt"), models, StandardCharsets.UTF_8);
        Files.writeString(media.resolve("clothing/clothing.xml"), undead(npcs, itemModels, media), StandardCharsets.UTF_8);
        Files.writeString(media.resolve("scripts/gielinor_bodies.txt"), bodies(itemModels, media), StandardCharsets.UTF_8);
        Files.writeString(media.resolve("scripts/gielinor_animals.txt"), animals(npcs, itemModels, scripts, zomboid, media), StandardCharsets.UTF_8);
        itemModels.writeGuidTable(media);

        StringBuilder script = new StringBuilder("module " + MODULE + "\n{\n    imports\n    {\n        Base\n    }\n");
        StringBuilder names = new StringBuilder("{\n");
        Map<ItemConverter.Role, Integer> counts = new TreeMap<>();
        for (int i = 0; i < kept.size(); i++) {
            Built b = kept.get(i);
            script.append("\n    item ").append(b.type).append("\n    {\n");
            b.properties.forEach((k, v) -> script.append("        ").append(k).append(" = ").append(v).append(",\n"));
            b.components.forEach(c -> script.append(c.text(8)));
            script.append("    }\n");
            names.append("  \"").append(MODULE).append('.').append(b.type).append("\": \"").append(json(b.item.name)).append('"')
                .append(i + 1 < kept.size() ? ",\n" : "\n");
            counts.merge(b.role, 1, Integer::sum);
        }
        script.append("}\n");
        names.append("}\n");
        Files.writeString(media.resolve("scripts/gielinor_items.txt"), script, StandardCharsets.UTF_8);
        Files.writeString(media.resolve("lua/shared/Translate/EN/ItemName.json"), names, StandardCharsets.UTF_8);
        Files.writeString(media.resolve("lua/shared/Gielinor_Loot.lua"), lootList(kept), StandardCharsets.UTF_8);

        System.out.println("wrote " + kept.size() + " items (" + (built.size() - kept.size()) + " without an icon left out), " + modelCount + " weapon models, " + groundCount + " ground models, " + wornCount + " wearables");
        counts.forEach((role, n) -> System.out.println("  " + role.name().toLowerCase() + ": " + n));
    }

    private static String json(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
