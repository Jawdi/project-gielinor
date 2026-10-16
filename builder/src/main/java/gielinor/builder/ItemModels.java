package gielinor.builder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.definitions.ModelDefinition;

/**
 * Items' models. Weapons get the model the game's player holds them by,
 * lined up like the vanilla weapon they copy so that weapon's hand and
 * ground attachments fit unchanged. Everything else gets the model it lies on
 * the ground as, standing on its base the way Zomboid's ground items do.
 */
final class ItemModels {
    /**
     * The game draws dropped items larger than life so they stand out;
     * Zomboid's ground items are true to size. This brings them close.
     */
    private static final double GROUND_SCALE = 0.6;

    private final ModelExporter exporter;
    private final ZomboidItems.Scripts scripts;
    private final File install;
    private final double[] hand;
    private final Map<String, Mesh> templateMeshes = new HashMap<>();
    /** Whether Zomboid's meshes are wound with normals facing out, measured on a vanilla mesh. */
    private final boolean outward;
    private final Skeleton skeleton;
    private final HumanFit fit;
    /** Clothing definitions written so far: their file paths and GUIDs, for `fileGuidTable.xml`. */
    private final Map<String, String> clothingGuids = new java.util.LinkedHashMap<>();

    ItemModels(ModelExporter exporter, ZomboidItems.Scripts scripts, File install) throws IOException {
        this.exporter = exporter;
        this.scripts = scripts;
        this.install = install;
        this.hand = exporter.hand();
        Mesh reference = templateMesh(scripts.models().get("HandAxe"));
        this.outward = reference == null || reference.outwardVote() >= 0;
        this.skeleton = Skeleton.read(new File(install, "media/models_X/Skinned/MaleBody.x").toPath());
        this.fit = new HumanFit(skeleton);
    }

    /**
     * Writes a worn item as Zomboid clothing: its worn models put on
     * Zomboid's skeleton as the player wears them, a palette texture, and a
     * clothing definition. Returns false if it has nothing to show.
     */
    boolean exportWorn(ItemDefinition item, String type, ItemConverter.Slot slot, Path media) throws IOException {
        Mesh mesh = merged(new int[]{item.maleModel0, item.maleModel1, item.maleModel2}, item, null, null);
        if (mesh.faces.isEmpty()) {
            return false;
        }
        Bound[] bounds = fitted(mesh, HumanFit.PLAYER_CROWN, false, slot != ItemConverter.Slot.CAPE);
        writeClothing("Gielinor_" + type, mesh, bounds, masks(slot, bounds), hatCategory(item), media);
        return true;
    }

    /**
     * Writes a whole creature (a zombie, say) as one skinned clothing piece
     * that hides the body under it entirely: its models merged and recoloured
     * as its definition says, scaled to a person's height, its arms read from
     * where it holds them (`findArms`) or taken to hang as the player's do.
     * Returns false if it has nothing to show.
     */
    boolean exportFigure(String name, int[] models, short[] recolorFind, short[] recolorReplace, boolean findArms, Path media) throws IOException {
        Mesh mesh = merged(models, null, recolorFind, recolorReplace);
        if (mesh.faces.isEmpty() || !standsUpright(mesh)) {
            return false;
        }
        double crown = 0;
        for (double[] v : mesh.vertices) {
            crown = Math.max(crown, -v[1]);
        }
        Bound[] bounds = fitted(mesh, crown, findArms, true);
        writeClothing(name, mesh, bounds, java.util.List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10), NO_HAIR_OR_BEARD, media);
        return true;
    }

    /**
     * Whether a creature stands on two legs like a person: much taller than
     * it is deep from front to back. Some of the game's zombies are rats and
     * the like, which can't be fitted onto a person.
     */
    private static boolean standsUpright(Mesh mesh) {
        double top = Double.MAX_VALUE;
        double bottom = -Double.MAX_VALUE;
        double front = Double.MAX_VALUE;
        double back = -Double.MAX_VALUE;
        for (double[] v : mesh.vertices) {
            top = Math.min(top, v[1]);
            bottom = Math.max(bottom, v[1]);
            front = Math.min(front, v[2]);
            back = Math.max(back, v[2]);
        }
        return back - front < 0.7 * (bottom - top);
    }

    /**
     * Writes a creature fitted onto one of Zomboid's animals: its mesh skinned
     * to that animal's skeleton (read from `animalMesh`), and its colours as
     * a body texture `textures/Body/<name>.png` for the animal's breeds to
     * use. Returns false if it has nothing to show.
     */
    boolean exportAnimal(String name, int[] models, short[] recolorFind, short[] recolorReplace, Path animalMesh, Path media) throws IOException {
        Mesh mesh = merged(models, null, recolorFind, recolorReplace);
        if (mesh.faces.isEmpty()) {
            return false;
        }
        Skeleton animal = Skeleton.read(animalMesh);
        Bound[] bounds = new AnimalFit(animal).fit(mesh.vertices);
        for (int i = 0; i < bounds.length; i++) {
            mesh.vertices.set(i, bounds[i].position());
        }
        if (mesh.outwardVote() >= 0 != outward) {
            mesh.flipWinding();
        }
        Palette palette = new Palette();
        mesh.colours.forEach(palette::add);
        Path meshDir = media.resolve("models_X/Skinned/Gielinor");
        Path textureDir = media.resolve("textures/Body");
        Files.createDirectories(meshDir);
        Files.createDirectories(textureDir);
        mesh.writeSkinnedX(meshDir.resolve(name + ".X"), name, name + ".png", palette, animal, bounds);
        palette.write(textureDir.resolve(name + ".png"));
        return true;
    }

    /** How tall the body made of these identity kits stands, in game units. */
    double kitCrown(java.util.List<Integer> kits) throws IOException {
        java.util.List<Integer> models = new java.util.ArrayList<>();
        for (int kit : kits) {
            for (int m : exporter.kitModels(kit)) {
                models.add(m);
            }
        }
        double crown = 0;
        for (double[] v : merged(models.stream().mapToInt(Integer::intValue).toArray(), null, null, null).vertices) {
            crown = Math.max(crown, -v[1]);
        }
        return crown;
    }

    /** The body parts a player can choose from (see {@link ModelExporter#selectableKits}). */
    java.util.List<net.runelite.cache.definitions.KitDefinition> selectableKits() throws IOException {
        return exporter.selectableKits();
    }

    /** The colour the game models skin in on every body part; the player's skin colour takes its place. */
    static final int SKIN_SOURCE = 4550;

    /**
     * The pieces a body part is split into, so the player's colours can be
     * laid on it: its own colours, its skin, and the faces the player's
     * chosen colour (hair, clothing or shoes) takes the place of, in its
     * first shade and its second. All but the first are white and take their
     * colour from a tint.
     */
    enum Piece {
        FIXED("", "fixed"), SKIN("Skin", "skin"), COLOUR("Colour", "colour"), COLOUR2("Colour2", "colour2");

        /** Added to the piece's item and model names. */
        final String suffix;
        /** The piece's name in the mod's kit list and its slots. */
        final String key;

        Piece(String suffix, String key) {
            this.suffix = suffix;
            this.key = key;
        }
    }

    /**
     * Writes one of the game's body parts (an identity kit: a hairstyle, a
     * torso, a pair of legs) as up to three pieces named `name` plus each
     * {@link Piece}'s suffix, hiding the parts of Zomboid's body in `masks`.
     * Faces modelled in `colourSource` and `secondSource` (packed HSL; -1
     * for none) go in the colour pieces, skin in the skin piece. `crown` is the whole body's
     * height, so a body's parts all scale alike. Returns the pieces written.
     */
    java.util.Set<Piece> exportKit(String name, int kit, int colourSource, int secondSource, double crown, java.util.List<Integer> masks, Path media) throws IOException {
        Mesh mesh = merged(exporter.kitModels(kit), null, null, null);
        java.util.Set<Piece> written = java.util.EnumSet.noneOf(Piece.class);
        if (mesh.faces.isEmpty()) {
            return written;
        }
        Bound[] bounds = fitted(mesh, crown, false, true);
        String hat = masks.contains(0) ? NO_HAIR_OR_BEARD : null;
        for (Piece piece : Piece.values()) {
            Mesh part = new Mesh();
            part.vertices.addAll(mesh.vertices);
            for (int f = 0; f < mesh.faces.size(); f++) {
                int source = mesh.sourceColours.get(f);
                Piece of = source == SKIN_SOURCE ? Piece.SKIN : source == colourSource ? Piece.COLOUR
                    : source == secondSource ? Piece.COLOUR2 : Piece.FIXED;
                if (of == piece) {
                    part.faces.add(mesh.faces.get(f));
                    part.colours.add(piece == Piece.FIXED ? mesh.colours.get(f) : 0xFFFFFF);
                }
            }
            if (!part.faces.isEmpty()) {
                writeClothing(name + piece.suffix, part, bounds, masks, hat, piece != Piece.FIXED, media);
                written.add(piece);
            }
        }
        return written;
    }

    /** Models merged into one mesh in game units, recoloured by an item or a creature's definition. */
    private Mesh merged(int[] models, ItemDefinition item, short[] recolorFind, short[] recolorReplace) throws IOException {
        Mesh mesh = new Mesh();
        for (int id : models) {
            ModelDefinition definition = id > 0 ? exporter.load(id) : null;
            if (definition == null) {
                continue;
            }
            if (recolorFind != null) {
                for (int i = 0; i < recolorFind.length; i++) {
                    definition.recolor(recolorFind[i], recolorReplace[i]);
                }
            }
            Mesh part = exporter.rawMesh(definition, item);
            int base = mesh.vertices.size();
            mesh.vertices.addAll(part.vertices);
            for (int[] f : part.faces) {
                mesh.faces.add(new int[]{f[0] + base, f[1] + base, f[2] + base});
            }
            mesh.colours.addAll(part.colours);
            mesh.sourceColours.addAll(part.sourceColours);
            mesh.groups.addAll(part.groups);
        }
        return mesh;
    }

    /** Puts a model on Zomboid's skeleton (see {@link HumanFit#fit}), winds its faces Zomboid's way, and returns how each vertex is bound. */
    private Bound[] fitted(Mesh mesh, double crown, boolean findArms, boolean armsAllowed) {
        Bound[] bounds = fit.fit(mesh, crown, findArms, armsAllowed);
        if (mesh.outwardVote() >= 0 != outward) {
            mesh.flipWinding();
        }
        return bounds;
    }

    /**
     * The body parts a worn piece hides (Zomboid's character mask parts: 1
     * torso, 2 pelvis, 3 and 5 the left and right arm, 4 and 6 the hands, 7
     * and 9 the legs, 8 and 10 the feet), so the body doesn't show through
     * it. A part is hidden only if the piece really covers it.
     */
    private static java.util.List<Integer> masks(ItemConverter.Slot slot, Bound[] bounds) {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (Bound b : bounds) {
            used.add(b.bone());
        }
        java.util.List<Integer> out = new java.util.ArrayList<>();
        switch (slot) {
            case BODY -> {
                if (used.contains("Bip01_Spine") && used.contains("Bip01_Spine1")) {
                    out.add(1);
                }
                if (used.contains("Bip01_L_UpperArm") && used.contains("Bip01_L_Forearm")) {
                    out.add(3);
                }
                if (used.contains("Bip01_R_UpperArm") && used.contains("Bip01_R_Forearm")) {
                    out.add(5);
                }
            }
            case LEGS -> {
                if (used.contains("Bip01_Pelvis")) {
                    out.add(2);
                }
                if (used.contains("Bip01_L_Thigh") && used.contains("Bip01_L_Calf")) {
                    out.add(7);
                }
                if (used.contains("Bip01_R_Thigh") && used.contains("Bip01_R_Calf")) {
                    out.add(9);
                }
            }
            case HANDS -> {
                if (used.contains("Bip01_L_Hand")) {
                    out.add(4);
                }
                if (used.contains("Bip01_R_Hand")) {
                    out.add(6);
                }
            }
            case FEET -> {
                if (used.contains("Bip01_L_Foot")) {
                    out.add(8);
                }
                if (used.contains("Bip01_R_Foot")) {
                    out.add(10);
                }
            }
            default -> {
            }
        }
        return out;
    }

    /**
     * Zomboid's hat category for a piece that takes the place of hair and
     * beard: its character's hair and beard aren't drawn while it's worn.
     */
    private static final String NO_HAIR_OR_BEARD = "nohairnobeard";
    /** The game's equipment slots for the hair and the jaw (the beard). */
    private static final int HAIR = 8;
    private static final int JAW = 11;

    /**
     * The hat category of a worn item: whether it hides its wearer's hair
     * and beard, as the game's items name the extra body parts they hide.
     */
    private static String hatCategory(ItemDefinition item) {
        boolean hair = item.wearPos2 == HAIR || item.wearPos3 == HAIR;
        boolean jaw = item.wearPos2 == JAW || item.wearPos3 == JAW;
        return hair && jaw ? NO_HAIR_OR_BEARD : hair ? "nohair" : jaw ? "nobeard" : null;
    }

    /**
     * Writes a skinned clothing piece: its mesh, palette and clothing
     * definition. `hatCategory` (or null) says whether it hides hair or beard.
     */
    private void writeClothing(String name, Mesh mesh, Bound[] bounds, java.util.List<Integer> masks, String hatCategory, Path media) throws IOException {
        writeClothing(name, mesh, bounds, masks, hatCategory, false, media);
    }

    /** The same; a `tintable` piece takes its colour from the tint the mod gives it. */
    private void writeClothing(String name, Mesh mesh, Bound[] bounds, java.util.List<Integer> masks, String hatCategory, boolean tintable, Path media) throws IOException {
        Palette palette = new Palette();
        mesh.colours.forEach(palette::add);
        Path meshDir = media.resolve("models_X/Skinned/Clothes/Gielinor");
        Path textureDir = media.resolve("textures/Clothes/Gielinor");
        Files.createDirectories(meshDir);
        Files.createDirectories(textureDir);
        mesh.writeSkinnedX(meshDir.resolve(name + ".X"), name, name + ".png", palette, skeleton, bounds);
        palette.write(textureDir.resolve(name + ".png"));

        String guid = java.util.UUID.nameUUIDFromBytes(("ProjectGielinor:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String path = "media/clothing/clothingItems/" + name + ".xml";
        clothingGuids.put(path, guid);
        Files.createDirectories(media.resolve("clothing/clothingItems"));
        // The same mesh serves both bodies until there's a fit for the female skeleton.
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n<clothingItem>\r\n");
        xml.append("\t<m_GUID>").append(guid).append("</m_GUID>\r\n");
        xml.append("\t<m_MaleModel>skinned\\clothes\\gielinor\\").append(name).append("</m_MaleModel>\r\n");
        xml.append("\t<m_FemaleModel>skinned\\clothes\\gielinor\\").append(name).append("</m_FemaleModel>\r\n");
        xml.append("\t<m_AltMaleModel></m_AltMaleModel>\r\n\t<m_AltFemaleModel></m_AltFemaleModel>\r\n");
        xml.append("\t<m_Static>false</m_Static>\r\n\t<m_AllowRandomHue>false</m_AllowRandomHue>\r\n");
        xml.append("\t<m_AllowRandomTint>").append(tintable).append("</m_AllowRandomTint>\r\n\t<m_AttachBone></m_AttachBone>\r\n");
        // Zomboid cuts clothing worn under other clothing with mask images
        // laid out like its own body texture; a palette texture isn't, so
        // the cuts would fall in random places. "none" leaves it whole, as
        // Zomboid does for bags.
        xml.append("\t<m_MasksFolder>none</m_MasksFolder>\r\n");
        if (hatCategory != null) {
            xml.append("\t<m_HatCategory>").append(hatCategory).append("</m_HatCategory>\r\n");
        }
        for (int mask : masks) {
            xml.append("\t<m_Masks>").append(mask).append("</m_Masks>\r\n");
        }
        xml.append("\t<textureChoices>clothes\\gielinor\\").append(name).append("</textureChoices>\r\n");
        xml.append("</clothingItem>\r\n");
        Files.writeString(media.resolve("clothing/clothingItems/" + name + ".xml"), xml);
    }

    /** The clothing definitions written so far, by name, with their GUIDs. */
    String guidOf(String name) {
        return clothingGuids.get("media/clothing/clothingItems/" + name + ".xml");
    }

    /** Writes `fileGuidTable.xml`, which tells the game which GUID each clothing definition has. */
    void writeGuidTable(Path media) throws IOException {
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n<fileGuidTable>\r\n");
        clothingGuids.forEach((path, guid) -> out.append("\t<files>\r\n\t\t<path>").append(path).append("</path>\r\n\t\t<guid>")
            .append(guid).append("</guid>\r\n\t</files>\r\n"));
        out.append("</fileGuidTable>\r\n");
        Files.writeString(media.resolve("fileGuidTable.xml"), out);
    }

    /** The vanilla model block a role's template weapon is drawn with, if any. */
    private ZomboidItems.Block templateModel(ItemConverter.Role role) {
        ZomboidItems.Block item = scripts.items().get(role.template);
        String name = item == null ? null : ItemConverter.templateModel(item);
        return name == null ? null : scripts.models().get(name);
    }

    private Mesh templateMesh(ZomboidItems.Block model) throws IOException {
        String mesh = model.properties().get("mesh");
        if (mesh == null) {
            return null;
        }
        if (!templateMeshes.containsKey(mesh)) {
            File dir = new File(install, "media/models_X/" + mesh).getParentFile();
            String base = new File(mesh).getName();
            File[] found = dir.listFiles((d, n) -> n.equalsIgnoreCase(base + ".x"));
            templateMeshes.put(mesh, found == null || found.length == 0 ? null : Mesh.readX(found[0].toPath()));
        }
        return templateMeshes.get(mesh);
    }

    /**
     * Writes a weapon's mesh and palette into the pack and returns its model
     * block, or null if it can't be built (no model, no template mesh).
     */
    String exportHeld(ItemDefinition item, String type, ItemConverter.Role role, Path media) throws IOException {
        ZomboidItems.Block model = templateModel(role);
        if (model == null) {
            return null;
        }
        Mesh template = templateMesh(model);
        int id = item.maleModel0 > 0 ? item.maleModel0 : item.inventoryModel;
        ModelDefinition definition = id > 0 ? exporter.load(id) : null;
        if (template == null || template.vertices.isEmpty() || definition == null) {
            return null;
        }
        Mesh mesh = exporter.mesh(definition, item);
        if (mesh.faces.isEmpty()) {
            return null;
        }
        ModelExporter.alignHeld(mesh, hand, template);
        write(mesh, type, media);
        return new ZomboidItems.Block("model " + type, files(type), model.children()).text(4);
    }

    /**
     * Writes an item's ground model (its inventory model, which the game
     * also draws it lying on the ground with) and returns its model block, or
     * null if it has none.
     */
    String exportGround(ItemDefinition item, String name, Path media) throws IOException {
        ModelDefinition definition = item.inventoryModel > 0 ? exporter.load(item.inventoryModel) : null;
        if (definition == null) {
            return null;
        }
        Mesh mesh = exporter.mesh(definition, item);
        if (mesh.faces.isEmpty()) {
            return null;
        }
        double k = GROUND_SCALE;
        double[] resize = {k * item.resizeX / 128.0, k * item.resizeY / 128.0, k * item.resizeZ / 128.0};
        double[] c = ModelExporter.centroid(mesh.vertices);
        double floor = mesh.vertices.stream().mapToDouble(p -> p[1]).min().orElse(0);
        for (int i = 0; i < mesh.vertices.size(); i++) {
            double[] p = mesh.vertices.get(i);
            mesh.vertices.set(i, new double[]{(p[0] - c[0]) * resize[0], (p[1] - floor) * resize[1], (p[2] - c[2]) * resize[2]});
        }
        if (mesh.outwardVote() >= 0 != outward) {
            mesh.flipWinding();
        }
        write(mesh, name, media);
        return new ZomboidItems.Block("model " + name, files(name), java.util.List.of()).text(4);
    }

    private static Map<String, String> files(String name) {
        Map<String, String> props = new java.util.LinkedHashMap<>();
        props.put("mesh", "Gielinor/" + name);
        props.put("texture", "Gielinor/" + name);
        return props;
    }

    private static void write(Mesh mesh, String name, Path media) throws IOException {
        Palette palette = new Palette();
        mesh.colours.forEach(palette::add);
        Files.createDirectories(media.resolve("models_X/Gielinor"));
        Files.createDirectories(media.resolve("textures/Gielinor"));
        mesh.writeX(media.resolve("models_X/Gielinor/" + name + ".X"), name, name + ".png", palette);
        palette.write(media.resolve("textures/Gielinor/" + name + ".png"));
    }
}
