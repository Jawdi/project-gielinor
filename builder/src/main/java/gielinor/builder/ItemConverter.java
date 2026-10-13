package gielinor.builder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.cache.definitions.ItemDefinition;

/**
 * Turns a Gielinor item into a Project Zomboid item. Each copies the vanilla
 * Zomboid item that plays the same part (a hand axe for an axe, an apple for
 * food), so it borrows that item's animations, sounds and held model, then
 * takes its own name, icon and weight, with damage scaled from its strength
 * bonus.
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
        FOOD("Apple"),
        DRINK("Apple"),
        /** Worn: clothing or armour. */
        WEAR(null),
        /** Carried, with no use of its own yet. */
        PLAIN(null);

        final String template;

        Role(String template) {
            this.template = template;
        }

        boolean isWeapon() {
            return this != FOOD && this != DRINK && this != WEAR && this != PLAIN;
        }
    }

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
     * 42 banners, 150 whips, 65 claws) and, failing that, its first option.
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
        String first = item.interfaceOptions == null ? null : item.interfaceOptions[0];
        if ("Eat".equalsIgnoreCase(first)) {
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
        "evolvedrecipe", "foodtype", "daysfresh", "daystotallyrotten", "tooltip", "weaponspritesbyindex");

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
        if (role.template != null) {
            ZomboidItems.Block template = templates.get(role.template);
            if (template == null) {
                return null;
            }
            template.properties().forEach((k, v) -> {
                if (!NOT_COPIED.contains(k.toLowerCase())) {
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
        switch (role) {
            case FOOD -> {
                props.put("HungerChange", "-15.0");
                props.put("ThirstChange", "0.0");
                props.put("Calories", "150.0");
            }
            case DRINK -> {
                props.put("HungerChange", "0.0");
                props.put("ThirstChange", "-20.0");
                props.put("Calories", "40.0");
            }
            default -> {
                if (role.isWeapon()) {
                    double max = maxDamage(strength);
                    props.put("MinDamage", number(max * 0.45));
                    props.put("MaxDamage", number(max));
                    if (role == Role.AXE) {
                        props.put("TreeDamage", Integer.toString(treeDamage(strength)));
                    }
                }
            }
        }
        return props;
    }
}
