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

    /** The outfit every Gielinor zombie is given. */
    static final String ZOMBIE_OUTFIT = "GielinorZombie";

    /**
     * The zombies' outfit (`clothing.xml`), and its name for the mod
     * (`lua/shared/Gielinor_Outfits.lua`), which knows it before Zomboid has
     * read its outfits. Zombies are zombified Gielinor folk: the mod gives
     * each a Gielinor body and clothes of its own. The outfit is empty; it
     * only gives each zombie a number Zomboid keeps through saves (its
     * persistent outfit), from which the mod picks the same look every time.
     */
    private static String zombieOutfits(Path media) throws IOException {
        String guid = java.util.UUID.nameUUIDFromBytes(("ProjectGielinor:outfit:" + ZOMBIE_OUTFIT).getBytes(StandardCharsets.UTF_8)).toString();
        String outfit = "\t\t<m_Name>" + ZOMBIE_OUTFIT + "</m_Name>\r\n\t\t<m_Guid>" + guid + "</m_Guid>\r\n"
            + "\t\t<m_Top>false</m_Top>\r\n\t\t<m_Pants>false</m_Pants>\r\n";
        Path lua = media.resolve("lua/shared/Gielinor_Outfits.lua");
        Files.createDirectories(lua.getParent());
        Files.writeString(lua, "-- Written by the Project Gielinor Content Builder: the content pack's zombie\n"
            + "-- outfits.\n\nGielinorOutfits = {\n    zombies = " + luaList(List.of(ZOMBIE_OUTFIT))
            + ",\n    monsters = {},\n}\n", StandardCharsets.UTF_8);
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n<outfitManager>\r\n"
            + "\t<m_FemaleOutfits>\r\n" + outfit + "\t</m_FemaleOutfits>\r\n"
            + "\t<m_MaleOutfits>\r\n" + outfit + "\t</m_MaleOutfits>\r\n</outfitManager>\r\n";
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
        Files.writeString(media.resolve("clothing/clothing.xml"), zombieOutfits(media), StandardCharsets.UTF_8);
        Files.writeString(media.resolve("scripts/gielinor_wardrobe.txt"), Wardrobe.write(itemModels, media), StandardCharsets.UTF_8);
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
