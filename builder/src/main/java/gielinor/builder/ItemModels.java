package gielinor.builder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
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
     * How a face of a body or clothing piece is coloured: one of a
     * {@link SharedPalette}'s shades, a fixed colour (through
     * {@link SharedPalette#fixed}), or -1 to leave the face out. `source` is
     * the colour the game models the face in (packed HSL), `rgb` how it
     * shows, `height` the face's middle's height in metres once fitted.
     */
    interface FaceColour {
        int of(int source, int rgb, double height, double across);
    }

    /**
     * Writes a piece of a Gielinor body or its clothes made of the game's
     * identity kits (`kits`: a torso and its sleeves, a hairstyle), fitted to
     * Zomboid's skeleton at the body's height `crown`. `colour` colours (or
     * leaves out) each face against `palette`, whose `choices` textures the
     * piece's clothing definition offers. `push` moves the piece out along its
     * surface (in metres; in if negative), so clothes sit just over the body
     * beneath. Returns false if no face is left.
     */
    boolean exportWardrobe(String name, int[] kits, double crown, double push, SharedPalette palette, int choices,
            FaceColour colour, Path media) throws IOException {
        return exportWardrobe(name, kits, new int[0], crown, push, palette, choices, colour, false, new double[0], media);
    }

    /**
     * The same, with the game's model pieces `pieces` added to the kits'
     * (such as a creature's bare torso), smooth shaded if `smooth` (see
     * {@link Mesh#smooth}), and with faces crossing any height in `cuts`
     * first cut in two there, so a band between cuts can be coloured however
     * large the model's faces are.
     */
    boolean exportWardrobe(String name, int[] kits, int[] pieces, double crown, double push, SharedPalette palette, int choices,
            FaceColour colour, boolean smooth, double[] cuts, Path media) throws IOException {
        java.util.List<Integer> models = new java.util.ArrayList<>();
        for (int kit : kits) {
            for (int m : exporter.kitModels(kit)) {
                models.add(m);
            }
        }
        for (int m : pieces) {
            models.add(m);
        }
        Mesh mesh = merged(models.stream().mapToInt(Integer::intValue).toArray(), null, null, null);
        if (mesh.faces.isEmpty()) {
            return false;
        }
        Bound[] bounds = fitted(mesh, crown, false, true);
        for (double cut : cuts) {
            bounds = cutAt(mesh, bounds, cut);
        }
        Mesh part = new Mesh();
        part.smooth = smooth;
        for (double[] v : mesh.vertices) {
            part.vertices.add(v.clone());
        }
        for (int f = 0; f < mesh.faces.size(); f++) {
            double height = 0;
            double across = 0;
            for (int v : mesh.faces.get(f)) {
                height += mesh.vertices.get(v)[1] / 3;
                across += Math.abs(mesh.vertices.get(v)[0]) / 3;
            }
            int c = colour.of(mesh.sourceColours.get(f), mesh.colours.get(f), height, across);
            if (c >= 0) {
                part.faces.add(mesh.faces.get(f));
                part.colours.add(c);
            }
        }
        if (part.faces.isEmpty()) {
            return false;
        }
        String[] parts = bodyParts(part, bounds);
        pushAlongSurface(part, push, bounds);
        Path meshDir = media.resolve("models_X/Skinned/Clothes/Gielinor");
        Files.createDirectories(meshDir);
        part.writeSkinnedX(meshDir.resolve(name + ".X"), name, palette.name() + "_0.png",
            (face, corner) -> palette.uv(parts[face], part.colours.get(face), face, corner), skeleton, bounds);
        java.util.List<String> textures = new java.util.ArrayList<>();
        for (int i = 0; i < choices; i++) {
            textures.add(palette.name() + "_" + i);
        }
        writeClothingDefinition(name, java.util.List.of(), null, false, textures, media);
        return true;
    }

    /** What a {@link FaceColour} gives a garment's bare skin, which goes to a body piece of its own. */
    static final int GARMENT_SKIN = -2;
    /** How far (in metres) inside a garment the skin under it lies. */
    private static final double LINING_IN = 0.004;

    /**
     * Writes a garment (a top, trousers, shoes) made of the game's identity
     * kits, as Zomboid's clothes are made: its texture laid out as Zomboid's
     * body texture is (see {@link GarmentLayout}), so blood, dirt and holes
     * fall on it as on Zomboid's, one texture for each of the colours in
     * `choices` (each a list of the shades {@link SharedPalette#shade} stands
     * in for). `colour` colours its faces, giving {@link #GARMENT_SKIN} for
     * bare skin: that's written as the body piece `skinName` in `skin`'s
     * colours, worn with the garment, so the garment's own textures needn't
     * come in every skin. The piece also carries the skin under the cloth,
     * which shows through tears. Returns whether the garment has any cloth.
     */
    boolean exportGarment(String name, String skinName, int[] kits, double crown, double push, FaceColour colour,
            int[][] choices, SharedPalette skin, int skinChoices, Path media) throws IOException {
        java.util.List<Integer> models = new java.util.ArrayList<>();
        for (int kit : kits) {
            for (int m : exporter.kitModels(kit)) {
                models.add(m);
            }
        }
        Mesh mesh = merged(models.stream().mapToInt(Integer::intValue).toArray(), null, null, null);
        if (mesh.faces.isEmpty()) {
            return false;
        }
        Bound[] bounds = fitted(mesh, crown, false, true);
        Mesh cloth = new Mesh();
        Mesh bare = new Mesh();
        for (double[] v : mesh.vertices) {
            cloth.vertices.add(v.clone());
            bare.vertices.add(v.clone());
        }
        for (int f = 0; f < mesh.faces.size(); f++) {
            double height = 0;
            double across = 0;
            for (int v : mesh.faces.get(f)) {
                height += mesh.vertices.get(v)[1] / 3;
                across += Math.abs(mesh.vertices.get(v)[0]) / 3;
            }
            int c = colour.of(mesh.sourceColours.get(f), mesh.colours.get(f), height, across);
            Mesh into = c == GARMENT_SKIN ? bare : c >= 0 ? cloth : null;
            if (into != null) {
                into.faces.add(mesh.faces.get(f));
                into.colours.add(c == GARMENT_SKIN ? SharedPalette.shade(0) : c);
            }
        }
        if (cloth.faces.isEmpty()) {
            return false;
        }
        Path meshDir = media.resolve("models_X/Skinned/Clothes/Gielinor");
        Path textureDir = media.resolve("textures/Clothes/Gielinor/Garments");
        Files.createDirectories(meshDir);
        Files.createDirectories(textureDir);
        GarmentLayout layout = new GarmentLayout(skeleton, cloth);
        pushAlongSurface(cloth, push, bounds);
        cloth.writeSkinnedX(meshDir.resolve(name + ".X"), name, "x.png", layout::uv, skeleton, bounds);
        java.util.List<String> textures = new java.util.ArrayList<>();
        for (int i = 0; i < choices.length; i++) {
            int[] shades = choices[i];
            layout.paint(textureDir.resolve(name + "_" + i + ".png"), f -> {
                int rgb = cloth.colours.get(f) & 0xffffff;
                int shade = rgb - SharedPalette.shade(0);
                return shade >= 0 && shade < shades.length ? shades[shade] : rgb;
            });
            textures.add("garments\\" + name + "_" + i);
        }
        writeClothingDefinition(name, java.util.List.of(), null, false, textures, media);
        // The skin under the cloth, which shows where the cloth tears (as
        // Zomboid's body does under its clothes): the cloth again, a little
        // inside it. It goes with the bare skin, which never tears.
        Mesh lining = new Mesh();
        for (double[] v : cloth.vertices) {
            lining.vertices.add(v.clone());
        }
        for (int f = 0; f < cloth.faces.size(); f++) {
            lining.faces.add(cloth.faces.get(f));
            lining.colours.add(SharedPalette.shade(0));
        }
        pushAlongSurface(lining, -push - LINING_IN, bounds);
        pushAlongSurface(bare, push, bounds);
        int offset = bare.vertices.size();
        bare.vertices.addAll(lining.vertices);
        for (int f = 0; f < lining.faces.size(); f++) {
            int[] face = lining.faces.get(f);
            bare.faces.add(new int[]{face[0] + offset, face[1] + offset, face[2] + offset});
            bare.colours.add(lining.colours.get(f));
        }
        Bound[] bareBounds = new Bound[bounds.length * 2];
        System.arraycopy(bounds, 0, bareBounds, 0, bounds.length);
        System.arraycopy(bounds, 0, bareBounds, bounds.length, bounds.length);
        {
            String[] parts = bodyParts(bare, bareBounds);
            bare.writeSkinnedX(meshDir.resolve(skinName + ".X"), skinName, skin.name() + "_0.png",
                (face, corner) -> skin.uv(parts[face], bare.colours.get(face), face, corner), skeleton, bareBounds);
            java.util.List<String> skinTextures = new java.util.ArrayList<>();
            for (int i = 0; i < skinChoices; i++) {
                skinTextures.add(skin.name() + "_" + i);
            }
            writeClothingDefinition(skinName, java.util.List.of(), null, false, skinTextures, media);
        }
        return true;
    }

    /** Whether {@link #exportGarment} wrote a bare-skin piece for the garment it last wrote. */
    boolean wroteSkin(String skinName, Path media) {
        return Files.exists(media.resolve("models_X/Skinned/Clothes/Gielinor/" + skinName + ".X"));
    }

    /**
     * Cuts every face crossing the height `y` into a face on one side and two
     * on the other, keeping each face's winding and colours. A new vertex
     * lies on the edge it cuts and moves with the bone of the edge's nearer
     * end. Returns the bounds with the new vertices'.
     */
    private static Bound[] cutAt(Mesh mesh, Bound[] bounds, double y) {
        List<Bound> all = new java.util.ArrayList<>(java.util.Arrays.asList(bounds));
        Map<Long, Integer> made = new java.util.HashMap<>();
        List<int[]> faces = new java.util.ArrayList<>();
        List<Integer> colours = new java.util.ArrayList<>();
        List<Integer> sources = new java.util.ArrayList<>();
        for (int f = 0; f < mesh.faces.size(); f++) {
            int[] face = mesh.faces.get(f);
            boolean[] above = new boolean[3];
            int count = 0;
            for (int k = 0; k < 3; k++) {
                above[k] = mesh.vertices.get(face[k])[1] >= y;
                count += above[k] ? 1 : 0;
            }
            List<int[]> pieces = new java.util.ArrayList<>();
            if (count == 0 || count == 3) {
                pieces.add(face);
            } else {
                // The corner alone on its side, then the other two in winding order.
                int lone = 0;
                for (int k = 0; k < 3; k++) {
                    if (above[k] != above[(k + 1) % 3] && above[k] != above[(k + 2) % 3]) {
                        lone = k;
                    }
                }
                int l = face[lone];
                int p = face[(lone + 1) % 3];
                int q = face[(lone + 2) % 3];
                int lp = edgePoint(mesh, all, made, l, p, y);
                int lq = edgePoint(mesh, all, made, l, q, y);
                pieces.add(new int[]{l, lp, lq});
                pieces.add(new int[]{lp, p, q});
                pieces.add(new int[]{lp, q, lq});
            }
            for (int[] piece : pieces) {
                faces.add(piece);
                colours.add(mesh.colours.get(f));
                sources.add(mesh.sourceColours.get(f));
            }
        }
        mesh.faces.clear();
        mesh.faces.addAll(faces);
        mesh.colours.clear();
        mesh.colours.addAll(colours);
        mesh.sourceColours.clear();
        mesh.sourceColours.addAll(sources);
        return all.toArray(new Bound[0]);
    }

    /** The vertex where the edge from `a` to `b` crosses the height `y`, made once per edge. */
    private static int edgePoint(Mesh mesh, List<Bound> bounds, Map<Long, Integer> made, int a, int b, double y) {
        long key = (long) Math.min(a, b) << 32 | Math.max(a, b);
        return made.computeIfAbsent(key, k -> {
            double[] pa = mesh.vertices.get(a);
            double[] pb = mesh.vertices.get(b);
            double t = (y - pa[1]) / (pb[1] - pa[1]);
            double[] p = {pa[0] + (pb[0] - pa[0]) * t, y, pa[2] + (pb[2] - pa[2]) * t};
            Bound near = bounds.get(t < 0.5 ? a : b);
            mesh.vertices.add(p);
            bounds.add(new Bound(p, near.bones(), near.weights()));
            return mesh.vertices.size() - 1;
        });
    }

    /** The blood mask body part (see {@link SharedPalette#PARTS}) of the bone each place on the body moves with. */
    private static final Map<String, String> BONE_PARTS = Map.ofEntries(
        Map.entry("Bip01_L_Hand", "HandL"), Map.entry("Bip01_R_Hand", "HandR"),
        Map.entry("Bip01_L_Forearm", "LArmL"), Map.entry("Bip01_R_Forearm", "LArmR"),
        Map.entry("Bip01_L_UpperArm", "UArmL"), Map.entry("Bip01_R_UpperArm", "UArmR"),
        Map.entry("Bip01_Spine1", "Chest"), Map.entry("Bip01_Spine", "Stomach"), Map.entry("Bip01_Pelvis", "Groin"),
        Map.entry("Bip01_Head", "Head"), Map.entry("Bip01_Neck", "Neck"),
        Map.entry("Bip01_L_Thigh", "ULegL"), Map.entry("Bip01_R_Thigh", "ULegR"),
        Map.entry("Bip01_L_Calf", "LLegL"), Map.entry("Bip01_R_Calf", "LLegR"),
        Map.entry("Bip01_L_Foot", "FootL"), Map.entry("Bip01_R_Foot", "FootR"));

    /**
     * The body part each face covers, for where Zomboid paints its blood:
     * the part of the bone most of its corners move with; the chest, stomach
     * and hips' faces that look backwards are the back. Both games face their
     * models along -z.
     */
    private String[] bodyParts(Mesh mesh, Bound[] bounds) {
        double sign = outward ? 1 : -1;
        String[] out = new String[mesh.faces.size()];
        for (int f = 0; f < out.length; f++) {
            Map<String, Integer> votes = new HashMap<>();
            for (int v : mesh.faces.get(f)) {
                votes.merge(bounds[v].bones()[0], 1, Integer::sum);
            }
            String bone = votes.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
            String part = BONE_PARTS.getOrDefault(bone, "Chest");
            boolean torso = part.equals("Chest") || part.equals("Stomach") || part.equals("Groin");
            if (torso && sign * mesh.normal(f)[2] > 0) {
                part = "Back";
            }
            out[f] = part;
        }
        return out;
    }

    /**
     * The way along a limb bone, from its joint towards its child's, or null
     * for the body, head and feet.
     */
    private double[] limbAxis(String bone) {
        String side = bone.contains("_L_") ? "_L_" : bone.contains("_R_") ? "_R_" : null;
        if (side == null) {
            return null;
        }
        String[] from = bone.endsWith("UpperArm") ? new String[]{"UpperArm", "Forearm"}
            : bone.endsWith("Forearm") || bone.endsWith("Hand") ? new String[]{"Forearm", "Hand"}
            : bone.endsWith("Thigh") ? new String[]{"Thigh", "Calf"}
            : bone.endsWith("Calf") ? new String[]{"Calf", "Foot"}
            : null;
        if (from == null) {
            return null;
        }
        double[] axis = ModelExporter.sub(skeleton.bind("Bip01" + side + from[1]), skeleton.bind("Bip01" + side + from[0]));
        double length = Math.sqrt(ModelExporter.dot(axis, axis));
        return ModelExporter.scale(axis, 1 / length);
    }

    /**
     * Moves each vertex `distance` along its faces' average outward normal
     * (across its limb only, for a limb's vertex; `bounds` gives each
     * vertex's bones).
     */
    private void pushAlongSurface(Mesh mesh, double distance, Bound[] bounds) {
        if (distance == 0) {
            return;
        }
        // Zomboid's meshes are wound one way; a face's normal points out if
        // they're wound with normals out (see `outward`), else in.
        double sign = outward ? 1 : -1;
        double[][] sums = new double[mesh.vertices.size()][3];
        for (int f = 0; f < mesh.faces.size(); f++) {
            double[] n = mesh.normal(f);
            for (int v : mesh.faces.get(f)) {
                for (int k = 0; k < 3; k++) {
                    sums[v][k] += n[k];
                }
            }
        }
        for (int v = 0; v < mesh.vertices.size(); v++) {
            double[] n = sums[v];
            // A limb moves only across itself: the game's limbs are blocks
            // meeting end to end at the joints, and pushed along the limb
            // too, their ends would part there.
            double[] axis = limbAxis(bounds[v].bones()[0]);
            if (axis != null) {
                n = ModelExporter.sub(n, ModelExporter.scale(axis, ModelExporter.dot(n, axis)));
            }
            double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            if (len == 0) {
                continue;
            }
            double[] p = mesh.vertices.get(v);
            for (int k = 0; k < 3; k++) {
                p[k] += sign * distance * n[k] / len;
            }
        }
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
        writeClothingDefinition(name, masks, hatCategory, tintable, java.util.List.of(name), media);
    }

    /** Writes a clothing definition for the model `name`, offering `textures` (in `textures/Clothes/Gielinor`). */
    private void writeClothingDefinition(String name, java.util.List<Integer> masks, String hatCategory, boolean tintable,
            java.util.List<String> textures, Path media) throws IOException {
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
        for (String texture : textures) {
            xml.append("\t<textureChoices>clothes\\gielinor\\").append(texture).append("</textureChoices>\r\n");
        }
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
        writeHeld(mesh, type, media);
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

    /** Writes a held item's mesh with a texture cell for each face, so Zomboid's blood streaks along it (see {@link FaceAtlas}). */
    private static void writeHeld(Mesh mesh, String name, Path media) throws IOException {
        FaceAtlas atlas = new FaceAtlas(mesh);
        Files.createDirectories(media.resolve("models_X/Gielinor"));
        Files.createDirectories(media.resolve("textures/Gielinor"));
        mesh.writeX(media.resolve("models_X/Gielinor/" + name + ".X"), name, name + ".png", atlas);
        atlas.write(media.resolve("textures/Gielinor/" + name + ".png"));
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
