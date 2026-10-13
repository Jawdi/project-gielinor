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

    ItemModels(ModelExporter exporter, ZomboidItems.Scripts scripts, File install) throws IOException {
        this.exporter = exporter;
        this.scripts = scripts;
        this.install = install;
        this.hand = exporter.hand();
        Mesh reference = templateMesh(scripts.models().get("HandAxe"));
        this.outward = reference == null || reference.outwardVote() >= 0;
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
