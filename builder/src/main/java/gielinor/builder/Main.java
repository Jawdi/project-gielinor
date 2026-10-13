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
 * Project Gielinor's content builder. Reads the game cache of your own Old
 * School RuneScape install and writes the content pack the Project Gielinor
 * mod needs into Zomboid's mods folder: item scripts, icons and names. Nothing
 * from the game is shipped with the mod; this builds it on your machine.
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

    private record Built(ItemDefinition item, String type, ItemConverter.Role role, Map<String, String> properties) {
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
            cache = Locations.findCache().orElseThrow(() -> new IllegalStateException(
                "No Old School RuneScape cache found. Install the game, log in once, or pass --cache <folder>."));
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
            List<Built> built = build(items, scripts.items());
            NpcManager npcs = new NpcManager(store);
            npcs.load();
            writePack(out.toPath(), built, icons, itemModels, npcs);
        }
    }

    /** Every item worth having, once per name, lowest id first. */
    private static List<Built> build(ItemManager items, Map<String, ZomboidItems.Block> templates) {
        List<ItemDefinition> all = new ArrayList<>(items.getItems());
        all.sort(Comparator.comparingInt(d -> d.id));
        Set<String> names = new HashSet<>();
        List<Built> out = new ArrayList<>();
        for (ItemDefinition d : all) {
            boolean variant = d.notedTemplate != -1 || d.placeholderTemplateId != -1 || d.boughtTemplateId != -1;
            if (variant || d.name == null || d.name.isBlank() || d.name.equals("null") || d.inventoryModel <= 0) {
                continue;
            }
            if (!names.add(d.name.toLowerCase())) {
                continue;
            }
            String type = ItemConverter.typeName(d.name);
            ItemConverter.Role role = ItemConverter.roleOf(d);
            Map<String, String> props = ItemConverter.properties(d, type, role, templates);
            if (props != null) {
                out.add(new Built(d, type, role, props));
            }
        }
        return out;
    }

    /** Writes the pack in Build 42's layout: `42/mod.info`, `42/media`, and a `common` folder. */
    /**
     * Every distinct zombie in the game as a full-body clothing piece, each
     * its own zombie outfit (`GielinorZombie_<npc id>`) for the mod to put
     * among Zomboid's zombies. Returns the outfits' `clothing.xml`.
     */
    private static String zombies(NpcManager npcs, ItemModels itemModels, Path media) throws IOException {
        List<NpcDefinition> all = new ArrayList<>(npcs.getNpcs());
        all.sort(Comparator.comparingInt(n -> n.id));
        Set<String> seen = new HashSet<>();
        StringBuilder male = new StringBuilder();
        StringBuilder female = new StringBuilder();
        int count = 0;
        for (NpcDefinition n : all) {
            if (n.name == null || !n.name.toLowerCase().contains("zombie") || n.models == null || n.models.length == 0) {
                continue;
            }
            String look = java.util.Arrays.toString(n.models) + java.util.Arrays.toString(n.recolorToReplace);
            if (!seen.add(look)) {
                continue;
            }
            String name = "Gielinor_Zombie_" + n.id;
            if (!itemModels.exportFigure(name, n.models, n.recolorToFind, n.recolorToReplace, media)) {
                continue;
            }
            String guid = itemModels.guidOf(name);
            String outfitGuid = java.util.UUID.nameUUIDFromBytes(("ProjectGielinor:outfit:" + name).getBytes(StandardCharsets.UTF_8)).toString();
            String outfit = "\t\t<m_Name>GielinorZombie_" + n.id + "</m_Name>\r\n\t\t<m_Guid>" + outfitGuid + "</m_Guid>\r\n"
                + "\t\t<m_Top>false</m_Top>\r\n\t\t<m_Pants>false</m_Pants>\r\n"
                + "\t\t<m_items>\r\n\t\t\t<itemGUID>" + guid + "</itemGUID>\r\n\t\t</m_items>\r\n";
            male.append("\t<m_MaleOutfits>\r\n").append(outfit).append("\t</m_MaleOutfits>\r\n");
            female.append("\t<m_FemaleOutfits>\r\n").append(outfit).append("\t</m_FemaleOutfits>\r\n");
            count++;
        }
        System.out.println("  zombie looks: " + count);
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n<outfitManager>\r\n" + female + male + "</outfitManager>\r\n";
    }

    private static void writePack(Path out, List<Built> built, IconRenderer icons, ItemModels itemModels, NpcManager npcs) throws IOException {
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
            kept.add(new Built(b.item, type, b.role, props));
        }
        models.append("}\n");
        Files.writeString(media.resolve("scripts/gielinor_models.txt"), models, StandardCharsets.UTF_8);
        Files.writeString(media.resolve("clothing/clothing.xml"), zombies(npcs, itemModels, media), StandardCharsets.UTF_8);
        itemModels.writeGuidTable(media);

        StringBuilder script = new StringBuilder("module " + MODULE + "\n{\n    imports\n    {\n        Base\n    }\n");
        StringBuilder names = new StringBuilder("{\n");
        Map<ItemConverter.Role, Integer> counts = new TreeMap<>();
        for (int i = 0; i < kept.size(); i++) {
            Built b = kept.get(i);
            script.append("\n    item ").append(b.type).append("\n    {\n");
            b.properties.forEach((k, v) -> script.append("        ").append(k).append(" = ").append(v).append(",\n"));
            script.append("    }\n");
            names.append("  \"").append(MODULE).append('.').append(b.type).append("\": \"").append(json(b.item.name)).append('"')
                .append(i + 1 < kept.size() ? ",\n" : "\n");
            counts.merge(b.role, 1, Integer::sum);
        }
        script.append("}\n");
        names.append("}\n");
        Files.writeString(media.resolve("scripts/gielinor_items.txt"), script, StandardCharsets.UTF_8);
        Files.writeString(media.resolve("lua/shared/Translate/EN/ItemName.json"), names, StandardCharsets.UTF_8);

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
