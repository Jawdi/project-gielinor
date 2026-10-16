package gielinor.builder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.cache.definitions.ItemDefinition;

/**
 * Turns a Gielinor item into a Project Zomboid item. Each copies the vanilla
 * Zomboid item that plays the same part (a hand axe for an axe, a hammer for
 * a hammer, salmon for a fish), so it borrows that item's animations, sounds,
 * held model and the tags Zomboid's crafting and actions look for, then takes
 * its own name, icon and weight, with damage scaled from its strength bonus.
 */
final class ItemConverter {
    private ItemConverter() {
    }

    /** Item params in the game cache. */
    static final int PARAM_MELEE_STRENGTH = 10;

    /** The equipment slot a weapon sits in. */
    static final int WEAPON_SLOT = 3;

    /** What an item becomes in Zomboid. */
    enum Role {
        AXE("HandAxe"),
        PICKAXE("PickAxe"),
        SLASH("Machete"),
        STAB("HuntingKnife"),
        TWO_HANDED("Katana"),
        BLUNT("LeadPipe"),
        POLEARM("SpearCrafted"),
        STAFF("BaseballBat"),
        /** A tool Zomboid has too (a hammer, a bucket): its template is chosen by name. */
        TOOL(null),
        /** Food: its template is chosen by what it is. */
        FOOD(null),
        /** Drink, as a container holding its drink. */
        DRINK("Wine"),
        /** Worn: clothing or armour. */
        WEAR(null),
        /** Carried, with no use of its own yet. */
        PLAIN(null);

        final String template;

        Role(String template) {
            this.template = template;
        }

        boolean isWeapon() {
            return template != null && this != DRINK;
        }
    }

    /**
     * Gielinor's tools that Zomboid has a counterpart for, by name: each
     * becomes a copy of Zomboid's, so it works wherever Zomboid's does
     * (a hammer builds, a needle sews, a bucket carries water).
     */
    private static final Map<String, String> TOOLS = Map.ofEntries(
        Map.entry("hammer", "Hammer"),
        Map.entry("saw", "Saw"),
        Map.entry("knife", "KitchenKnife"),
        Map.entry("needle", "Needle"),
        Map.entry("shears", "Scissors"),
        Map.entry("chisel", "MetalworkingChisel"),
        Map.entry("tinderbox", "MagnesiumFirestarter"),
        Map.entry("spade", "Shovel"),
        Map.entry("rake", "Rake"),
        Map.entry("fishing rod", "FishingRod"),
        Map.entry("fly fishing rod", "FishingRod"),
        Map.entry("rope", "Rope"),
        Map.entry("bucket", "BucketEmpty"),
        Map.entry("bucket of water", "BucketEmpty"),
        Map.entry("bucket of milk", "BucketEmpty"),
        Map.entry("pot", "Pot"),
        Map.entry("bowl", "Bowl"),
        Map.entry("bowl of water", "Bowl"),
        Map.entry("jug", "WaterBottle"),
        Map.entry("jug of water", "WaterBottle"),
        Map.entry("vial", "WaterBottle"),
        Map.entry("vial of water", "WaterBottle"),
        Map.entry("empty cup", "Teacup"),
        Map.entry("gardening trowel", "HandShovel"),
        Map.entry("fishing bait", "BaitFish"));

    /**
     * The liquid an item holds, by a word in its name, first match wins
     * (Zomboid's fluid names). Items whose name names no liquid hold water.
     */
    private static final String[][] FLUIDS = {
        {"wine", "Wine"}, {"mead", "Mead"}, {"cider", "Cider"}, {"beer", "Beer"}, {"ale", "Beer"},
        {"bitter", "Beer"}, {"stout", "Beer"}, {"brandy", "Brandy"}, {"vodka", "Vodka"}, {"whisky", "Whiskey"},
        {"gin", "Gin"}, {"rum", "Rum"}, {"tea", "Tea"}, {"coffee", "Coffee"}, {"milk", "CowMilk"},
        {"juice", "JuiceApple"}, {"water", "Water"},
    };

    /** Words naming fish and meat, which cook like Zomboid's. */
    private static final String[] FISH = {"shrimp", "anchov", "sardine", "herring", "trout", "salmon", "pike", "tuna",
        "lobster", "swordfish", "monkfish", "shark", "karambwan", "manta", "turtle", "bass", "cod", "mackerel",
        "anglerfish", "eel", "crab", "fish"};
    private static final String[] MEAT = {"meat", "beef", "chicken", "rabbit", "bear", "rat"};
    private static final String[] BAKED = {"bread", "cake", "pie", "pizza", "stew", "potato"};

    /**
     * Where a worn item goes on a Zomboid character, by the game's equipment
     * slot: head, cape, neck, body, legs, hands, feet and ring. Capes take the
     * scarf slot so they don't take the place of a backpack. Shields and ammo
     * have no Zomboid place yet.
     */
    enum Slot {
        HEAD(0, "base:hat", "Head"),
        CAPE(1, "base:scarf", null),
        NECK(2, "base:necklace", null),
        BODY(4, "base:jacket", "Jacket"),
        LEGS(7, "base:pants", "Trousers"),
        HANDS(9, "base:hands", "Hands"),
        FEET(10, "base:shoes", "Shoes"),
        RING(12, "base:right_ringfinger", null);

        final int equipment;
        final String bodyLocation;
        final String bloodLocation;

        Slot(int equipment, String bodyLocation, String bloodLocation) {
            this.equipment = equipment;
            this.bodyLocation = bodyLocation;
            this.bloodLocation = bloodLocation;
        }

        static Slot of(int equipment) {
            for (Slot s : values()) {
                if (s.equipment == equipment) {
                    return s;
                }
            }
            return null;
        }
    }

    /**
     * Sorts an item by its category (the cache's item category numbers:
     * 35 axes, 67 pickaxes, 21 slashing swords, 25 daggers and stabbing
     * swords, 61 two-handed swords, 39 maces, 26 warhammers, 92 mjolnirs,
     * 36 spears, 66 and 273 halberds, 1588 partisans, 1193 scythes, 1 staves,
     * 42 banners, 150 whips, 65 claws), its name for tools Zomboid has too,
     * and failing those its first option (raw food, which can't be eaten
 * until it's cooked, counts as food).
     * Bows, crossbows and thrown weapons stay plain until there's ranged combat.
     */
    static Role roleOf(ItemDefinition item) {
        if (item.wearPos1 == WEAPON_SLOT) {
            switch (item.category) {
                case 35: return Role.AXE;
                case 67: return Role.PICKAXE;
                case 21, 150, 65: return Role.SLASH;
                case 25: return Role.STAB;
                case 61: return Role.TWO_HANDED;
                case 39, 26, 92: return Role.BLUNT;
                case 36, 66, 273, 1588, 1193: return Role.POLEARM;
                case 1, 42: return Role.STAFF;
                default: break;
            }
        }
        if (TOOLS.containsKey(item.name.toLowerCase())) {
            return Role.TOOL;
        }
        String first = item.interfaceOptions == null ? null : item.interfaceOptions[0];
        if ("Eat".equalsIgnoreCase(first) || isRaw(item) && item.wearPos1 < 0) {
            return Role.FOOD;
        }
        if ("Drink".equalsIgnoreCase(first)) {
            return Role.DRINK;
        }
        if (Slot.of(item.wearPos1) != null && item.maleModel0 > 0) {
            return Role.WEAR;
        }
        return Role.PLAIN;
    }

    /** The vanilla item an item copies, or null for none. */
    static String templateOf(ItemDefinition item, Role role) {
        String name = item.name.toLowerCase();
        return switch (role) {
            case TOOL -> TOOLS.get(name);
            case FOOD -> containsAny(name, FISH) ? "Salmon" : containsAny(name, MEAT) ? "Steak"
                : containsAny(name, BAKED) ? "Bread" : "Apple";
            default -> role.template;
        };
    }

    /** Whether an item comes raw, needing cooking before it's safe to eat. */
    static boolean isRaw(ItemDefinition item) {
        return item.name.toLowerCase().startsWith("raw ");
    }

    /** Item params holding defence against stab, slash and crush attacks. */
    private static final int[] PARAM_DEFENCE = {5, 6, 7};

    /**
     * How much a worn item protects against bites and scratches, out of 100:
     * its best melee defence bonus d as 100d / (d + 60), so a bronze helm
     * gives about 6 and a rune platebody about 57.
     */
    static int protection(ItemDefinition item) {
        int best = 0;
        if (item.params != null) {
            for (int p : PARAM_DEFENCE) {
                if (item.params.get(p) instanceof Integer i) {
                    best = Math.max(best, i);
                }
            }
        }
        return (int) Math.round(100.0 * best / (best + 60));
    }

    /** Properties never copied from a template: its own looks, name and crafting ties. */
    private static final Set<String> NOT_COPIED = Set.of(
        "icon", "iconsfortexture", "weight", "displayname", "onbreak", "researchablerecipes",
        "evolvedrecipe", "foodtype", "daysfresh", "daystotallyrotten", "tooltip", "weaponspritesbyindex",
        "iconcolormask", "iconfluidmask");
    /** What makes food raw: left off food that comes cooked. */
    private static final Set<String> COOKING = Set.of("iscookable", "dangerousuncooked", "minutestocook", "minutestoburn");

    /** The vanilla model a template weapon is drawn with. */
    static String templateModel(ZomboidItems.Block template) {
        String one = template.properties().get("WeaponSprite");
        if (one != null) {
            return one;
        }
        String many = template.properties().get("WeaponSpritesByIndex");
        return many == null ? null : many.split(";")[0].trim();
    }

    /** An item's type name: the words run together, each capitalised, letters and digits only. */
    static String typeName(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : name.split("[^A-Za-z0-9]+")) {
            if (!word.isEmpty()) {
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        if (out.isEmpty() || Character.isDigit(out.charAt(0))) {
            out.insert(0, "Item");
        }
        return out.toString();
    }

    /**
     * How hard a weapon hits in Zomboid terms: Zomboid's melee weapons do
     * about 0.5 (a hammer) to 3 (a sledgehammer) at most, and Gielinor's
     * strength bonuses run from 0 to about 130.
     */
    static double maxDamage(int meleeStrength) {
        return 0.5 + Math.max(meleeStrength, 0) / 40.0;
    }

    /**
     * How much an axe takes off a tree a swing. Zomboid's hand axe does 15
     * and its felling axe 35; axes' strength bonuses run from 5 (bronze) to
     * 42 (dragon), which lands them at 12 to 37.
     */
    static int treeDamage(int meleeStrength) {
        return (int) Math.round(8 + 0.7 * Math.max(meleeStrength, 0));
    }

    static String number(double v) {
        double r = Math.round(v * 100) / 100.0;
        return r == Math.rint(r) ? String.format("%.1f", r) : Double.toString(r);
    }

    static int meleeStrength(ItemDefinition item) {
        if (item.params == null) {
            return 0;
        }
        Object v = item.params.get(PARAM_MELEE_STRENGTH);
        return v instanceof Integer i ? i : 0;
    }

    /** The item's script properties, or null if its template is missing. */
    static Map<String, String> properties(ItemDefinition item, String type, Role role, Map<String, ZomboidItems.Block> templates) {
        Map<String, String> props = new LinkedHashMap<>();
        String templateName = templateOf(item, role);
        if (templateName != null) {
            ZomboidItems.Block template = templates.get(templateName);
            if (template == null) {
                return null;
            }
            boolean raw = isRaw(item);
            template.properties().forEach((k, v) -> {
                String key = k.toLowerCase();
                if (!NOT_COPIED.contains(key) && (raw || role != Role.FOOD || !COOKING.contains(key))) {
                    props.put(k, v);
                }
            });
        } else if (role == Role.WEAR) {
            Slot slot = Slot.of(item.wearPos1);
            int protection = protection(item);
            props.put("DisplayCategory", protection > 0 ? "ProtectiveGear" : "Clothing");
            props.put("ItemType", "base:clothing");
            props.put("BodyLocation", slot.bodyLocation);
            props.put("ClothingItem", "Gielinor_" + type);
            if (slot.bloodLocation != null) {
                props.put("BloodLocation", slot.bloodLocation);
            }
            if (protection > 0) {
                props.put("BiteDefense", Integer.toString(protection));
                props.put("ScratchDefense", Integer.toString(protection));
            }
        } else {
            props.put("DisplayCategory", "Material");
            props.put("ItemType", "base:normal");
        }
        // The name also goes in the translation file; this is the fallback
        // the game reads where that isn't loaded.
        props.put("DisplayName", item.name.replace(",", ""));
        props.put("Weight", number(Math.max(item.weight / 1000.0, 0.01)));
        props.put("Icon", "Gielinor_" + type);
        int strength = meleeStrength(item);
        if (role == Role.FOOD) {
            props.put("HungerChange", "-15.0");
            props.put("Calories", "150.0");
            if (isRaw(item)) {
                props.put("IsCookable", "true");
                props.put("DangerousUncooked", "true");
                props.putIfAbsent("MinutesToCook", "20");
                props.putIfAbsent("MinutesToBurn", "60");
            }
        } else if (role.isWeapon()) {
            double max = maxDamage(strength);
            props.put("MinDamage", number(max * 0.45));
            props.put("MaxDamage", number(max));
            if (role == Role.AXE) {
                props.put("TreeDamage", Integer.toString(treeDamage(strength)));
            }
        }
        return props;
    }

    /**
     * The item's components: the template's, with a container's liquid set
     * by the item's name (a jug of wine holds wine, an empty jug nothing).
     */
    static List<ZomboidItems.Block> components(ItemDefinition item, Role role, Map<String, ZomboidItems.Block> templates) {
        String templateName = templateOf(item, role);
        ZomboidItems.Block template = templateName == null ? null : templates.get(templateName);
        List<ZomboidItems.Block> out = new ArrayList<>();
        if (template == null) {
            return out;
        }
        for (ZomboidItems.Block child : template.children()) {
            if (!child.header().equals("component FluidContainer")) {
                out.add(child);
                continue;
            }
            Map<String, String> props = new LinkedHashMap<>(child.properties());
            props.remove("PickRandomFluid");
            String fluid = fluidOf(item);
            List<ZomboidItems.Block> fluids = fluid == null ? List.of()
                : List.of(new ZomboidItems.Block("Fluids", Map.of("fluid", fluid + ":1.0"), List.of()));
            out.add(new ZomboidItems.Block(child.header(), props, fluids));
        }
        return out;
    }

    /** The liquid a container comes holding, or null if it comes empty. */
    private static String fluidOf(ItemDefinition item) {
        String name = item.name.toLowerCase();
        boolean empty = TOOLS.containsKey(name) && !name.contains(" of ") && !name.equals("empty cup");
        if (empty || name.equals("empty cup")) {
            return null;
        }
        for (String[] f : FLUIDS) {
            if (name.matches(".*\\b" + f[0] + ".*")) {
                return f[1];
            }
        }
        return "Water";
    }

    private static boolean containsAny(String name, String[] words) {
        for (String w : words) {
            if (name.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
