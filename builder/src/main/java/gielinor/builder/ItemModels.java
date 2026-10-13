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
    private final BodyFit fit;
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
        this.fit = new BodyFit(skeleton, exporter.defaultBody());
    }

    /**
     * Writes a worn item as Zomboid clothing: its worn models fitted onto
     * Zomboid's T-pose and skinned to its skeleton, a palette texture, and a
     * clothing definition. Returns false if it has nothing to show.
     */
    boolean exportWorn(ItemDefinition item, String type, ItemConverter.Slot slot, Path media) throws IOException {
        Mesh mesh = merged(new int[]{item.maleModel0, item.maleModel1, item.maleModel2}, item, null, null);
        if (mesh.faces.isEmpty()) {
            return false;
        }
        String[] bones = fitted(mesh, BodyFit.Pose.PLAYER, slot != ItemConverter.Slot.CAPE);
        writeClothing("Gielinor_" + type, mesh, bones, masks(slot, bones), media);
        return true;
    }

    /**
     * Writes a whole creature (a zombie, say) as one skinned clothing piece
     * that hides the body under it entirely: its models merged and recoloured
     * as its definition says, its pose read from its arms. Returns false if
     * it has nothing to show.
     */
    boolean exportFigure(String name, int[] models, short[] recolorFind, short[] recolorReplace, Path media) throws IOException {
        Mesh mesh = merged(models, null, recolorFind, recolorReplace);
        if (mesh.faces.isEmpty()) {
            return false;
        }
        String[] bones = fitted(mesh, BodyFit.Pose.of(mesh.vertices), true);
        writeClothing(name, mesh, bones, java.util.List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10), media);
        return true;
    }

    /** Writes the player's own body, made of identity kits (its default look), as a full-body piece. */
    boolean exportKitBody(String name, int[] kits, Path media) throws IOException {
        java.util.List<Integer> models = new java.util.ArrayList<>();
        for (int kit : kits) {
            for (int m : exporter.kitModels(kit)) {
                models.add(m);
            }
        }
        return exportFigure(name, models.stream().mapToInt(Integer::intValue).toArray(), null, null, media);
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
        }
        return mesh;
    }

    /** Fits every vertex onto the T-pose, winds faces Zomboid's way, and returns each vertex's bone. */
    private String[] fitted(Mesh mesh, BodyFit.Pose pose, boolean armsAllowed) {
        String[] bones = new String[mesh.vertices.size()];
        for (int i = 0; i < mesh.vertices.size(); i++) {
            double[] v = mesh.vertices.get(i);
            BodyFit.Bound bound = fit.fit(v[0], v[1], v[2], pose, armsAllowed);
            mesh.vertices.set(i, bound.position());
            bones[i] = bound.bone();
        }
        if (mesh.outwardVote() >= 0 != outward) {
            mesh.flipWinding();
        }
        return bones;
    }

    /**
     * The body parts a worn piece hides (Zomboid's character mask parts: 1
     * torso, 2 pelvis, 3 and 5 the left and right arm, 4 and 6 the hands, 7
     * and 9 the legs, 8 and 10 the feet), so the body doesn't show through
     * it. A part is hidden only if the piece really covers it.
     */
    private static java.util.List<Integer> masks(ItemConverter.Slot slot, String[] bones) {
        java.util.Set<String> used = new java.util.HashSet<>(java.util.Arrays.asList(bones));
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

    /** Writes a skinned clothing piece: its mesh, palette and clothing definition. */
    private void writeClothing(String name, Mesh mesh, String[] bones, java.util.List<Integer> masks, Path media) throws IOException {
        Palette palette = new Palette();
        mesh.colours.forEach(palette::add);
        Path meshDir = media.resolve("models_X/Skinned/Clothes/Gielinor");
        Path textureDir = media.resolve("textures/Clothes/Gielinor");
        Files.createDirectories(meshDir);
        Files.createDirectories(textureDir);
        mesh.writeSkinnedX(meshDir.resolve(name + ".X"), name, name + ".png", palette, skeleton, bones);
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
        xml.append("\t<m_AllowRandomTint>false</m_AllowRandomTint>\r\n\t<m_AttachBone></m_AttachBone>\r\n");
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
