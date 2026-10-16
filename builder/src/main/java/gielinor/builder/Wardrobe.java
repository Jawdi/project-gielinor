package gielinor.builder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.cache.definitions.KitDefinition;

/**
 * Gielinor's people as Zomboid's are made: a body that can't be taken off,
 * and clothes over it that can. Both come from the game's character
 * designer (its identity kits and colours).
 *
 * The body is each body type's default torso, arms, hands, legs and feet
 * all in skin, with underwear (and on body type B a chest covering), worn
 * as body visuals by the mod; heads, hair and jaws are body too, one of each
 * per hairstyle and jaw. The clothes are Zomboid clothing items: a top for
 * every torso and sleeves together, trousers for every pair of legs, shoes
 * for every pair of feet. Every piece of a kind shares one
 * {@link SharedPalette}, so each colour the player can pick is one texture
 * (a `textureChoice`) for the lot.
 */
final class Wardrobe {
    private Wardrobe() {
    }

    /**
     * One of the colours a player picks, as the game's client recolours its
     * body models: the colour (packed HSL) the kits are modelled in and what
     * each choice turns it into, and the same for a second shade some kits
     * use alongside it (-1 and none where no kit does). The designer offers
     * the first `offered` choices; the rest are unlocked in the game.
     */
    record BodyColour(String name, int source, short[] choices, int secondSource, short[] secondChoices, int offered) {
        /** Choice `i`'s first and second shade as the models show them. */
        int[] shades(int i) {
            int first = ModelExporter.rgb(choices[i] & 0xffff);
            int second = i < secondChoices.length ? ModelExporter.rgb(secondChoices[i] & 0xffff) : first;
            return new int[]{first, second};
        }
    }

    static final BodyColour HAIR = new BodyColour("hair", 6798,
        new short[]{6798, 107, 10283, 16, 4797, 7744, 5799, 4634, -31839, 22433, 2983, -11343, 8, 5281, 10438, 3650, -27322, -21845, 200, 571, 908, 21830, 28946, -15701, -14010},
        -10304 & 0xffff,
        new short[]{6554, 115, 10304, 28, 5702, 7756, 5681, 4510, -31835, 22437, 2859, -11339, 16, 5157, 10446, 3658, -27314, -21965, 472, 580, 784, 21966, 28950, -15697, -14002},
        25);
    static final BodyColour TORSO = new BodyColour("torso", 8741,
        new short[]{8741, 12, -1506, -22374, 7735, 8404, 1701, -27106, 24094, 10153, -8915, 4783, 1341, 16578, -30533, 25239, 8, 5281, 10438, 3650, -27322, -21845, 200, 571, 908, 21830, 28946, -15701, -14010},
        9104,
        new short[]{9104, 10275, 7595, 3610, 7975, 8526, 918, -26734, 24466, 10145, -6882, 5027, 1457, 16565, -30545, 25486, 24, 5392, 10429, 3673, -27335, -21957, 192, 687, 412, 21821, 28835, -15460, -14019},
        29);
    static final BodyColour LEGS = new BodyColour("legs", 25238,
        new short[]{25238, 8742, 12, -1506, -22374, 7735, 8404, 1701, -27106, 24094, 10153, -8915, 4783, 1341, 16578, -30533, 8, 5281, 10438, 3650, -27322, -21845, 200, 571, 908, 21830, 28946, -15701, -14010},
        -1, new short[0], 29);
    static final BodyColour FEET = new BodyColour("feet", 4626, new short[]{4626, 11146, 6439, 12, 4758, 10270}, -1, new short[0], 6);
    static final BodyColour SKIN = new BodyColour("skin", ItemModels.SKIN_SOURCE,
        new short[]{4550, 4537, 5681, 5673, 5790, 6806, 8076, 4574, 17050, 0, 127, -31821, -17991},
        -1, new short[0], 8);
    static final List<BodyColour> COLOURS = List.of(HAIR, TORSO, LEGS, FEET, SKIN);

    /** The game's numbers for the body parts on body type A (body type B's are 7 more). */
    private static final int HAIR_PART = 0;
    private static final int JAW_PART = 1;
    private static final int TORSO_PART = 2;
    private static final int ARMS_PART = 3;
    private static final int HANDS_PART = 4;
    private static final int LEGS_PART = 5;
    private static final int FEET_PART = 6;
    private static final String[] PART_NAMES = {"hair", "jaw", "torso", "arms", "hands", "legs", "feet"};

    /** Each body type's default kits, by part (-1: none). */
    private static final int[][] DEFAULT_KITS = {
        {0, 10, 18, 26, 33, 36, 42},
        {45, 296, 56, 61, 67, 70, 79},
    };

    /**
     * A region of the body, which the clothes covering it hide (as Zomboid's
     * clothing masks its body): its name, and for each body type the designer
     * kits and the game's model pieces it's made of. No designer kit is a bare
     * torso (they're all shirts), so those are undressed creatures': body type
     * A has the Gamer's (NPC 1019) bare torso, the game's own undressed man;
     * body type B has a barbarian woman's (NPC
     * 3072) torso and bare arms, her fur top a chest covering. (Her fur shorts
     * join her legs to her hips in faces that stretch as she walks, so the
     * legs are a designer kit's, with briefs.)
     */
    private record Region(String name, int[][] kits, int[][] pieces) {
    }

    private static final List<Region> REGIONS = List.of(
        new Region("torso", new int[][]{{}, {}}, new int[][]{{315}, {11783}}),
        new Region("arms", new int[][]{{27}, {}}, new int[][]{{}, {11772}}),
        new Region("hands", new int[][]{{33}, {67}}, new int[][]{{}, {}}),
        new Region("legs", new int[][]{{36}, {70}}, new int[][]{{}, {}}),
        new Region("feet", new int[][]{{42}, {79}}, new int[][]{{}, {}}));
    /** The barbarian woman's fur colours (packed HSL); all else on a body is skin. */
    private static final java.util.Set<Integer> FUR = java.util.Set.of(5652, 5772);
    /**
     * The briefs: the legs between these heights (metres; the
     * crotch is about 0.46), but not the hands hanging beside them. Faces are
     * cut at these heights first, so the band follows them.
     */
    private static final double UNDERWEAR_FROM = 0.43;
    private static final double UNDERWEAR_TO = 0.56;
    private static final double UNDERWEAR_ACROSS = 0.14;
    /** The briefs' colour on each body type: body type B's match her fur top. */
    private static final int[] UNDERWEAR = {0xD8D2C4, ModelExporter.rgb(5772)};

    /** How far clothes sit out from the body, and the body in from Zomboid's skeleton line, in metres. */
    private static final double CLOTHES_OUT = 0.004;
    private static final double BODY_IN = -0.008;

    private static final String MODULE = Main.MODULE;

    /**
     * The clothes' names, by kit, from the game's own names for its styles
     * (the OSRS Wiki's Thessalia's Makeovers page lists them in the cache's
     * order), as a garment rather than a style ("Light buttons" is a
     * light-buttoned shirt). Kits added since have none; they're named by
     * their shape.
     */
    private static final Map<Integer, String> STYLE_NAMES = Map.ofEntries(
        // Body type A's tops.
        Map.entry(18, "Shirt"), Map.entry(19, "Light-buttoned shirt"), Map.entry(20, "Dark-buttoned shirt"),
        Map.entry(21, "Jacket"), Map.entry(22, "Shirt"), Map.entry(23, "Stitched shirt"), Map.entry(24, "Torn shirt"),
        Map.entry(25, "Two-toned shirt"), Map.entry(105, "Sweater"), Map.entry(106, "Buttoned shirt"), Map.entry(107, "Vest"),
        Map.entry(108, "Princely shirt"), Map.entry(109, "Ripped weskit"), Map.entry(110, "Torn weskit"),
        // Body type B's tops.
        Map.entry(56, "Top"), Map.entry(57, "Crop top"), Map.entry(58, "Polo neck"), Map.entry(59, "Simple top"),
        Map.entry(60, "Torn top"), Map.entry(89, "Sweater"), Map.entry(90, "Shirt"), Map.entry(91, "Vest"),
        Map.entry(92, "Frilly top"), Map.entry(93, "Corset"), Map.entry(94, "Bodice"),
        // Body type A's legs.
        Map.entry(36, "Trousers"), Map.entry(37, "Shorts"), Map.entry(38, "Flares"), Map.entry(39, "Turn-up trousers"),
        Map.entry(40, "Tatty trousers"), Map.entry(41, "Beach shorts"), Map.entry(100, "Princely trousers"),
        Map.entry(101, "Leggings"), Map.entry(102, "Striped trousers"), Map.entry(103, "Ripped trousers"),
        Map.entry(104, "Patched trousers"),
        // Body type B's legs.
        Map.entry(70, "Trousers"), Map.entry(71, "Skirt"), Map.entry(72, "Flares"), Map.entry(73, "Long skirt"),
        Map.entry(74, "Long narrow skirt"), Map.entry(75, "Tatty trousers"), Map.entry(76, "Turn-up trousers"),
        Map.entry(77, "Short skirt"), Map.entry(78, "Layered skirt"), Map.entry(135, "Sash skirt"),
        Map.entry(136, "Big-hemmed skirt"), Map.entry(137, "Sash trousers"), Map.entry(138, "Patterned skirt"),
        Map.entry(139, "Torn skirt"), Map.entry(140, "Patched skirt"));

    /**
     * Writes the body, its parts and the clothes, their palettes' textures,
     * and the mod's list of them (`lua/shared/Gielinor_Kits.lua`). Returns
     * their item script.
     */
    static String write(ItemModels models, Path media, Path zomboid) throws IOException {
        Path masks = zomboid.resolve("media/textures/BloodTextures");
        SharedPalette skin = new SharedPalette("GielinorSkin", 1, masks);
        SharedPalette hair = new SharedPalette("GielinorHair", 2, masks);
        SharedPalette tops = new SharedPalette("GielinorTop", 3, masks);
        SharedPalette legs = new SharedPalette("GielinorLegs", 2, masks);
        SharedPalette feet = new SharedPalette("GielinorFeet", 2, masks);

        StringBuilder script = new StringBuilder("module " + MODULE + "\n{\n    imports\n    {\n        Base\n    }\n");
        Map<Integer, List<KitDefinition>> byPart = new LinkedHashMap<>();
        for (KitDefinition kit : models.selectableKits()) {
            if (kit.bodyPartId >= 0 && kit.bodyPartId < 14) {
                byPart.computeIfAbsent(kit.bodyPartId, k -> new ArrayList<>()).add(kit);
            }
        }
        StringBuilder lua = new StringBuilder("-- Written by the Project Gielinor Content Builder: what a Gielinor body\n"
            + "-- and its clothes are made of, by body type (A and B): the body, each\n"
            + "-- hairstyle's head and hair, each jaw, the tops (by torso and sleeves),\n"
            + "-- trousers and shoes, each body type's default look, and the colours.\n\nGielinorKits = {\n");
        int counted = 0;
        for (int type = 0; type < 2; type++) {
            String t = type == 0 ? "A" : "B";
            int base = type * 7;
            List<Integer> defaults = new ArrayList<>();
            for (int kit : DEFAULT_KITS[type]) {
                if (kit >= 0) {
                    defaults.add(kit);
                }
            }
            double crown = models.kitCrown(defaults);
            lua.append("    ").append(t).append(" = {\n");

            // The body, region by region, in skin, smooth shaded so it doesn't
            // read as cloth. Body type B's fur keeps its colours; anything else
            // on a body (cuffs and the like on body type A's kits) is skin.
            int underwear = SharedPalette.fixed(UNDERWEAR[type]);
            lua.append("        body = {\n");
            for (Region region : REGIONS) {
                String name = "GielinorBody" + capital(region.name()) + "_" + t;
                boolean legsRegion = region.name().equals("legs");
                boolean cutBriefs = legsRegion;
                double[] cuts = cutBriefs ? new double[]{UNDERWEAR_FROM, UNDERWEAR_TO} : new double[0];
                if (models.exportWardrobe(name, region.kits()[type], region.pieces()[type], crown, BODY_IN, skin, SKIN.offered,
                        (source, rgb, height, across) -> {
                            if (cutBriefs && height >= UNDERWEAR_FROM && height < UNDERWEAR_TO && across < UNDERWEAR_ACROSS) {
                                return underwear;
                            }
                            return FUR.contains(source) ? SharedPalette.fixed(rgb) : SharedPalette.shade(0);
                        }, true, cuts, media)) {
                    script.append(item(name, "Gielinor body", null));
                    lua.append("            ").append(region.name()).append(" = \"").append(MODULE).append('.').append(name).append("\",\n");
                }
            }
            lua.append("        },\n");

            // Heads and hair, one pair per hairstyle.
            lua.append("        hair = {\n");
            for (KitDefinition kit : byPart.getOrDefault(base + HAIR_PART, List.of())) {
                String head = "GielinorHead_" + kit.getId();
                String hairName = "GielinorHair_" + kit.getId();
                boolean hasHead = models.exportWardrobe(head, new int[]{kit.getId()}, new int[0], crown, 0, skin, SKIN.offered,
                    (source, rgb, height, across) -> isHair(source) ? -1 : source == SKIN.source ? SharedPalette.shade(0) : SharedPalette.fixed(rgb),
                    true, new double[0], media);
                boolean hasHair = models.exportWardrobe(hairName, new int[]{kit.getId()}, crown, 0.002, hair, HAIR.offered,
                    (source, rgb, height, across) -> hairShade(source), media);
                if (!hasHead && !hasHair) {
                    continue;
                }
                lua.append("            { kit = ").append(kit.getId());
                if (hasHead) {
                    script.append(item(head, "Gielinor head", null));
                    lua.append(", head = \"").append(MODULE).append('.').append(head).append('"');
                }
                if (hasHair) {
                    script.append(item(hairName, "Gielinor hair", null));
                    lua.append(", hair = \"").append(MODULE).append('.').append(hairName).append('"');
                }
                lua.append(" },\n");
                counted++;
            }
            lua.append("        },\n");

            // Jaws: the chin in skin, the beard in the hair's colour.
            lua.append("        jaw = {\n");
            for (KitDefinition kit : byPart.getOrDefault(base + JAW_PART, List.of())) {
                String chin = "GielinorChin_" + kit.getId();
                String beard = "GielinorBeard_" + kit.getId();
                boolean hasChin = models.exportWardrobe(chin, new int[]{kit.getId()}, crown, 0, skin, SKIN.offered,
                    (source, rgb, height, across) -> isHair(source) ? -1 : source == SKIN.source ? SharedPalette.shade(0) : SharedPalette.fixed(rgb), media);
                boolean hasBeard = models.exportWardrobe(beard, new int[]{kit.getId()}, crown, 0.002, hair, HAIR.offered,
                    (source, rgb, height, across) -> hairShade(source), media);
                if (!hasChin && !hasBeard) {
                    continue;
                }
                lua.append("            { kit = ").append(kit.getId());
                if (hasChin) {
                    script.append(item(chin, "Gielinor chin", null));
                    lua.append(", chin = \"").append(MODULE).append('.').append(chin).append('"');
                }
                if (hasBeard) {
                    script.append(item(beard, "Gielinor beard", null));
                    lua.append(", beard = \"").append(MODULE).append('.').append(beard).append('"');
                }
                lua.append(" },\n");
                counted++;
            }
            lua.append("        },\n");

            // Tops: every torso with every pair of sleeves, as one item, and
            // every torso without (sleeveless, sleeves "0"), the arms bare.
            List<KitDefinition> torsos = byPart.getOrDefault(base + TORSO_PART, List.of());
            List<KitDefinition> arms = byPart.getOrDefault(base + ARMS_PART, List.of());
            lua.append("        torso = {").append(ids(torsos)).append("},\n");
            lua.append("        arms = {").append(ids(arms)).append("},\n");
            lua.append("        tops = {\n");
            for (KitDefinition torso : torsos) {
                List<Integer> sleeveIds = new ArrayList<>(List.of(0));
                arms.forEach(k -> sleeveIds.add(k.getId()));
                for (int sleeves : sleeveIds) {
                    String name = "GielinorTop_" + torso.getId() + "_" + sleeves;
                    int[] kits = sleeves == 0 ? new int[]{torso.getId()} : new int[]{torso.getId(), sleeves};
                    if (models.exportWardrobe(name, kits, crown, CLOTHES_OUT, tops, TORSO.offered * SKIN.offered,
                            (source, rgb, height, across) -> clothing(source, rgb, TORSO), media)) {
                        String style = STYLE_NAMES.getOrDefault(torso.getId(), type == 0 ? "Shirt" : "Top");
                        String topName = sleeves == 0 ? "Sleeveless " + style.toLowerCase() : style;
                        script.append(item(name, topName, sleeves == 0 ? CLOTHES_VEST : CLOTHES_TOP));
                        lua.append("            [\"").append(torso.getId()).append('_').append(sleeves)
                            .append("\"] = \"").append(MODULE).append('.').append(name).append("\",\n");
                        counted++;
                    }
                }
            }
            lua.append("        },\n");

            // Trousers and shoes.
            lua.append("        legs = {\n");
            for (KitDefinition kit : byPart.getOrDefault(base + LEGS_PART, List.of())) {
                String name = "GielinorTrousers_" + kit.getId();
                LegShape shape = new LegShape();
                if (models.exportWardrobe(name, new int[]{kit.getId()}, crown, CLOTHES_OUT, legs, LEGS.offered * SKIN.offered,
                        (source, rgb, height, across) -> {
                            int c = clothing(source, rgb, LEGS);
                            if (source != SKIN.source) {
                                shape.see(height, across);
                            }
                            return c;
                        }, media)) {
                    script.append(item(name, STYLE_NAMES.getOrDefault(kit.getId(), shape.name()), CLOTHES_TROUSERS));
                    lua.append("            { kit = ").append(kit.getId()).append(", item = \"").append(MODULE).append('.').append(name).append("\" },\n");
                    counted++;
                }
            }
            lua.append("        },\n        feet = {\n");
            List<KitDefinition> shoes = byPart.getOrDefault(base + FEET_PART, List.of());
            for (KitDefinition kit : shoes) {
                String name = "GielinorShoes_" + kit.getId();
                // The game's two styles, basic and elven, in that order.
                String shoeName = shoes.indexOf(kit) == 0 ? "Boots" : "Elven boots";
                if (models.exportWardrobe(name, new int[]{kit.getId()}, crown, CLOTHES_OUT + 0.002, feet, FEET.offered * SKIN.offered,
                        (source, rgb, height, across) -> clothing(source, rgb, FEET), media)) {
                    script.append(item(name, shoeName, CLOTHES_SHOES));
                    lua.append("            { kit = ").append(kit.getId()).append(", item = \"").append(MODULE).append('.').append(name).append("\" },\n");
                    counted++;
                }
            }
            lua.append("        },\n    },\n");
        }

        // Each body type's default look.
        lua.append("    defaults = {\n");
        for (int type = 0; type < 2; type++) {
            lua.append("        ").append(type == 0 ? "A" : "B").append(" = {");
            for (int p = 0; p < PART_NAMES.length; p++) {
                if (p != HANDS_PART && DEFAULT_KITS[type][p] >= 0) {
                    lua.append(' ').append(PART_NAMES[p]).append(" = ").append(DEFAULT_KITS[type][p]).append(',');
                }
            }
            lua.append(" },\n");
        }
        lua.append("    },\n");

        // The colours as the game's client shows them: each choice's colour
        // and its second shade (the first again where there's none).
        lua.append("    skins = ").append(SKIN.offered).append(",\n");
        lua.append("    colours = {\n");
        for (BodyColour colour : COLOURS) {
            lua.append("        ").append(colour.name()).append(" = {\n");
            for (int i = 0; i < colour.offered(); i++) {
                int[] s = colour.shades(i);
                lua.append(String.format("            {0x%06x, 0x%06x},", s[0], s[1])).append('\n');
            }
            lua.append("        },\n");
        }
        lua.append("    },\n}\n");
        Path file = media.resolve("lua/shared/Gielinor_Kits.lua");
        Files.createDirectories(file.getParent());
        Files.writeString(file, lua, StandardCharsets.UTF_8);

        // Each palette's textures, one per colour choice.
        Path textures = media.resolve("textures/Clothes/Gielinor");
        Files.createDirectories(textures);
        writeChoices(skin, SKIN, textures);
        writeChoices(hair, HAIR, textures);
        writeClothingChoices(tops, TORSO, textures);
        writeClothingChoices(legs, LEGS, textures);
        writeClothingChoices(feet, FEET, textures);
        System.out.println("  wardrobe: " + counted + " pieces");
        return script.append("}\n").toString();
    }

    private static void writeChoices(SharedPalette palette, BodyColour colour, Path dir) throws IOException {
        for (int i = 0; i < colour.offered(); i++) {
            palette.write(dir.resolve(palette.name() + "_" + i + ".png"), colour.shades(i));
        }
    }

    /**
     * A kind of clothes' textures: one for each colour and skin, choice
     * `colour * skins + skin`, the colour's shades first and the skin after.
     */
    private static void writeClothingChoices(SharedPalette palette, BodyColour colour, Path dir) throws IOException {
        int skins = SKIN.offered;
        for (int c = 0; c < colour.offered(); c++) {
            int[] shades = colour.shades(c);
            for (int k = 0; k < skins; k++) {
                int skinShade = SKIN.shades(k)[0];
                int[] all = colour.secondSource() >= 0 ? new int[]{shades[0], shades[1], skinShade} : new int[]{shades[0], skinShade};
                palette.write(dir.resolve(palette.name() + "_" + (c * skins + k) + ".png"), all);
            }
        }
    }

    /**
     * What a pair of legs is, from where its cloth reaches: a skirt joins the
     * legs (cloth across the middle well below the crotch, which trousers
     * have only at the crotch), a long one down to the ankles; shorts stop
     * above the knee; anything else is trousers.
     */
    private static final class LegShape {
        private double lowest = Double.MAX_VALUE;
        private boolean joined;

        void see(double height, double across) {
            lowest = Math.min(lowest, height);
            if (height < 0.33 && across < 0.02) {
                joined = true;
            }
        }

        String name() {
            if (joined) {
                return lowest < 0.15 ? "Long skirt" : "Skirt";
            }
            return lowest > 0.30 ? "Shorts" : "Trousers";
        }
    }

    private static String capital(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static boolean isHair(int source) {
        return source == HAIR.source || source == HAIR.secondSource;
    }

    /** A hair face's shade, or -1 for anything else. */
    private static int hairShade(int source) {
        return source == HAIR.source ? SharedPalette.shade(0) : source == HAIR.secondSource ? SharedPalette.shade(1) : -1;
    }

    /**
     * A clothing face: the chosen colour's shades, the kit's skin (in the
     * shade after them, the wearer's skin), and the kit's own colours.
     */
    private static int clothing(int source, int rgb, BodyColour colour) {
        if (source == SKIN.source) {
            return SharedPalette.shade(colour.secondSource() >= 0 ? 2 : 1);
        }
        if (source == colour.source) {
            return SharedPalette.shade(0);
        }
        if (source == colour.secondSource) {
            return SharedPalette.shade(1);
        }
        return SharedPalette.fixed(rgb);
    }

    private static String ids(List<KitDefinition> kits) {
        StringBuilder out = new StringBuilder();
        for (KitDefinition kit : kits) {
            out.append(out.length() == 0 ? "" : ", ").append(kit.getId());
        }
        return out.toString();
    }

    /** Properties of the clothes, as Zomboid's own long-sleeved shirt, trousers and shoes have them. */
    private static final String CLOTHES_TOP = """
                Icon = ShirtGeneric,
                BloodLocation = ShirtLongSleeves,
                BodyLocation = base:shirt,
                FabricType = Cotton,
                Insulation = 0.25,
                WindResistance = 0.15,
                WorldStaticModel = Shirt_Ground,
                Tags = base:ripclothingcotton,
        """;
    private static final String CLOTHES_VEST = """
                Icon = ShirtGeneric,
                BloodLocation = ShirtNoSleeves,
                BodyLocation = base:shirt,
                FabricType = Cotton,
                Insulation = 0.15,
                WindResistance = 0.1,
                WorldStaticModel = Shirt_Ground,
                Tags = base:ripclothingcotton,
        """;
    private static final String CLOTHES_TROUSERS = """
                Icon = Trousers_White,
                BloodLocation = Trousers,
                BodyLocation = base:pants,
                FabricType = Cotton,
                Insulation = 0.5,
                WindResistance = 0.4,
                WorldStaticModel = Trousers_Ground,
                Tags = base:ripclothingcotton,
        """;
    private static final String CLOTHES_SHOES = """
                Icon = Shoes,
                BiteDefense = 10,
                BloodLocation = Shoes,
                BodyLocation = base:shoes,
                BreakSound = ZombieRipClothing,
                ConditionLowerChanceOneIn = 12,
                ConditionMax = 24,
                Insulation = 0.25,
                RemoveOnBroken = false,
                RunSpeedModifier = 0.95,
                ScratchDefense = 20,
                StompPower = 2.1,
                WaterResistance = 0.25,
                WindResistance = 0.25,
                WorldStaticModel = Shoes_Ground,
        """;

    /**
     * An item for a piece: clothing with `properties`, or (null) a body
     * piece, which is never worn as clothing or found, and exists only so
     * Zomboid can find the model its body visual names.
     */
    private static String item(String name, String displayName, String properties) {
        StringBuilder out = new StringBuilder("\n    item ").append(name).append("\n    {\n")
            .append("        DisplayCategory = Clothing,\n")
            .append("        ItemType = base:clothing,\n")
            .append("        DisplayName = ").append(displayName).append(",\n")
            .append("        ClothingItem = ").append(name).append(",\n");
        if (properties == null) {
            out.append("        Icon = TshirtGeneric,\n        Weight = 0,\n        BodyLocation = gielinor:figure,\n");
        } else {
            out.append(properties);
        }
        return out.append("    }\n").toString();
    }
}
