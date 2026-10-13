package gielinor.builder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.KitDefinition;
import net.runelite.cache.definitions.loaders.KitLoader;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.loaders.ModelLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;
import net.runelite.cache.item.RSTextureProvider;
import net.runelite.cache.models.JagexColor;
import net.runelite.cache.SpriteManager;
import net.runelite.cache.TextureManager;

/**
 * Turns the game's models into Zomboid meshes. Game models are in units of
 * 1/128 of a tile with y pointing down; a player stands 202 units tall,
 * Zomboid's 0.98, which sets the scale. Faces keep their flat colours.
 */
final class ModelExporter {
    /** Zomboid model units a game model unit. */
    static final double SCALE = 0.98 / 202.0;
    /** The brightness the game draws models at by default. */
    private static final double BRIGHTNESS = JagexColor.BRIGHTNESS_HIGH;

    private final Store store;
    private final Index models;
    private final ModelLoader loader = new ModelLoader();
    private final RSTextureProvider textures;

    ModelExporter(Store store) throws IOException {
        this.store = store;
        this.models = store.getIndex(IndexType.MODELS);
        SpriteManager sprites = new SpriteManager(store);
        sprites.load();
        TextureManager textureManager = new TextureManager(store);
        textureManager.load();
        this.textures = new RSTextureProvider(textureManager, sprites);
    }

    /**
     * Where the game's player model holds weapons: the middle of the right
     * hand of the default male hands (identity kit 33), in Zomboid's units.
     */
    double[] hand() throws IOException {
        Index configs = store.getIndex(IndexType.CONFIGS);
        Archive kits = configs.getArchive(ConfigType.IDENTKIT.getId());
        ArchiveFiles files = kits.getFiles(store.getStorage().loadArchive(kits));
        KitDefinition kit = new KitLoader().load(33, files.findFile(33).getContents());
        List<double[]> right = new ArrayList<>();
        for (int id : kit.models) {
            ModelDefinition model = load(id);
            for (int i = 0; i < model.vertexCount; i++) {
                if (model.vertexX[i] < 0) {
                    right.add(new double[]{model.vertexX[i] * SCALE, -model.vertexY[i] * SCALE, model.vertexZ[i] * SCALE});
                }
            }
        }
        return centroid(right);
    }

    ModelDefinition load(int id) throws IOException {
        Archive archive = models.getArchive(id);
        if (archive == null) {
            return null;
        }
        return loader.load(id, archive.decompress(store.getStorage().loadArchive(archive)));
    }

    /** The models an identity kit (a body part of the player's look) is made of. */
    int[] kitModels(int kit) throws IOException {
        Index configs = store.getIndex(IndexType.CONFIGS);
        Archive kits = configs.getArchive(ConfigType.IDENTKIT.getId());
        ArchiveFiles files = kits.getFiles(store.getStorage().loadArchive(kits));
        return new KitLoader().load(kit, files.findFile(kit).getContents()).models;
    }

    /** The default male player's body (identity kits 18 torso, 26 arms, 33 hands, 36 legs, 42 feet), in game units. */
    List<double[]> defaultBody() throws IOException {
        Index configs = store.getIndex(IndexType.CONFIGS);
        Archive kits = configs.getArchive(ConfigType.IDENTKIT.getId());
        ArchiveFiles files = kits.getFiles(store.getStorage().loadArchive(kits));
        List<double[]> out = new ArrayList<>();
        for (int k : new int[]{18, 26, 33, 36, 42}) {
            KitDefinition kit = new KitLoader().load(k, files.findFile(k).getContents());
            for (int id : kit.models) {
                ModelDefinition model = load(id);
                for (int i = 0; i < model.vertexCount; i++) {
                    out.add(new double[]{model.vertexX[i], model.vertexY[i], model.vertexZ[i]});
                }
            }
        }
        return out;
    }

    /** A game model as a mesh in Zomboid's orientation (y up) and scale, recoloured as the item asks. */
    Mesh mesh(ModelDefinition model, ItemDefinition item) {
        Mesh mesh = rawMesh(model, item);
        for (int i = 0; i < mesh.vertices.size(); i++) {
            double[] p = mesh.vertices.get(i);
            mesh.vertices.set(i, new double[]{p[0] * SCALE, -p[1] * SCALE, p[2] * SCALE});
        }
        return mesh;
    }

    /** A game model as a mesh in the game's own units (y down), recoloured as the item asks. */
    Mesh rawMesh(ModelDefinition model, ItemDefinition item) {
        if (item != null) {
            if (item.colorFind != null) {
                for (int i = 0; i < item.colorFind.length; i++) {
                    model.recolor(item.colorFind[i], item.colorReplace[i]);
                }
            }
            if (item.textureFind != null) {
                for (int i = 0; i < item.textureFind.length; i++) {
                    model.retexture(item.textureFind[i], item.textureReplace[i]);
                }
            }
        }
        Mesh mesh = new Mesh();
        for (int i = 0; i < model.vertexCount; i++) {
            mesh.vertices.add(new double[]{model.vertexX[i], model.vertexY[i], model.vertexZ[i]});
        }
        for (int i = 0; i < model.faceCount; i++) {
            boolean seeThrough = model.faceTransparencies != null && (model.faceTransparencies[i] & 0xff) >= 250;
            if (model.faceColors[i] == -2 || seeThrough) {
                continue;
            }
            int texture = model.faceTextures == null ? -1 : model.faceTextures[i];
            int rgb = texture >= 0 ? textures.getAverageTextureRGB(texture) : JagexColor.HSLtoRGB(model.faceColors[i], BRIGHTNESS);
            mesh.faces.add(new int[]{model.faceIndices1[i], model.faceIndices2[i], model.faceIndices3[i]});
            mesh.colours.add(rgb);
        }
        return mesh;
    }

    /**
     * Lines a held weapon up the way Zomboid's weapon meshes lie: its long
     * axis along +y away from the hand, the grip (the point on that axis
     * nearest the hand) at the origin, and its second axis turned to match
     * the template weapon's, so the head or edge sits where the template's
     * does. `hand` is where the game's player model holds weapons.
     */
    static void alignHeld(Mesh mesh, double[] hand, Mesh template) {
        // Wind faces the way the template is wound, so the same sides face out.
        if (Integer.signum(mesh.outwardVote()) != Integer.signum(template.outwardVote())) {
            mesh.flipWinding();
        }
        double[] c = centroid(mesh.vertices);
        double[][] axes = principalAxes(mesh.vertices, c);
        double[] e1 = axes[0];
        double[] e2 = axes[1];
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (double[] p : mesh.vertices) {
            double t = dot(sub(p, c), e1);
            lo = Math.min(lo, t);
            hi = Math.max(hi, t);
        }
        double grip = Math.max(lo, Math.min(hi, dot(sub(hand, c), e1)));
        if (hi - grip < grip - lo) {
            e1 = scale(e1, -1);
            double t = lo;
            lo = -hi;
            hi = -t;
            grip = -grip;
        }
        // The template's second axis, across the weapon: which way its head or edge sticks out.
        double[] tc = centroid(template.vertices);
        double[][] taxes = principalAxes(template.vertices, tc);
        double[] f2 = taxes[1].clone();
        f2[1] = 0;
        double len = Math.sqrt(dot(f2, f2));
        f2 = len < 1e-9 ? new double[]{0, 0, 1} : scale(f2, 1 / len);
        double templateSkew = skew(template.vertices, tc, f2);
        if (skew(mesh.vertices, c, e2) * templateSkew < 0) {
            e2 = scale(e2, -1);
        }
        double[] e3 = cross(e1, e2);
        double[] f1 = {0, 1, 0};
        double[] f3 = cross(f1, f2);
        double[] origin = add(c, scale(e1, grip));
        for (int i = 0; i < mesh.vertices.size(); i++) {
            double[] d = sub(mesh.vertices.get(i), origin);
            double a = dot(d, e1);
            double b = dot(d, e2);
            double g = dot(d, e3);
            mesh.vertices.set(i, add(add(scale(f1, a), scale(f2, b)), scale(f3, g)));
        }
    }

    static double[] centroid(List<double[]> points) {
        double[] c = new double[3];
        for (double[] p : points) {
            for (int k = 0; k < 3; k++) {
                c[k] += p[k] / points.size();
            }
        }
        return c;
    }

    /** The covariance's eigenvectors, largest spread first (Jacobi rotations on the 3x3 matrix). */
    static double[][] principalAxes(List<double[]> points, double[] c) {
        double[][] m = new double[3][3];
        for (double[] p : points) {
            double[] d = sub(p, c);
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    m[i][j] += d[i] * d[j];
                }
            }
        }
        double[][] v = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (int sweep = 0; sweep < 50; sweep++) {
            for (int p = 0; p < 2; p++) {
                for (int q = p + 1; q < 3; q++) {
                    if (Math.abs(m[p][q]) < 1e-15) {
                        continue;
                    }
                    double theta = (m[q][q] - m[p][p]) / (2 * m[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    if (theta == 0) {
                        t = 1;
                    }
                    double cs = 1 / Math.sqrt(t * t + 1);
                    double sn = t * cs;
                    for (int k = 0; k < 3; k++) {
                        double mkp = m[k][p];
                        double mkq = m[k][q];
                        m[k][p] = cs * mkp - sn * mkq;
                        m[k][q] = sn * mkp + cs * mkq;
                    }
                    for (int k = 0; k < 3; k++) {
                        double mpk = m[p][k];
                        double mqk = m[q][k];
                        m[p][k] = cs * mpk - sn * mqk;
                        m[q][k] = sn * mpk + cs * mqk;
                    }
                    for (int k = 0; k < 3; k++) {
                        double vkp = v[k][p];
                        double vkq = v[k][q];
                        v[k][p] = cs * vkp - sn * vkq;
                        v[k][q] = sn * vkp + cs * vkq;
                    }
                }
            }
        }
        List<Integer> order = new ArrayList<>(List.of(0, 1, 2));
        order.sort((a, b) -> Double.compare(m[b][b], m[a][a]));
        double[][] out = new double[3][];
        for (int i = 0; i < 3; i++) {
            int col = order.get(i);
            out[i] = new double[]{v[0][col], v[1][col], v[2][col]};
        }
        return out;
    }

    /** Which side of the centroid most of the shape's bulk lies along an axis. */
    static double skew(List<double[]> points, double[] c, double[] axis) {
        double s = 0;
        for (double[] p : points) {
            double t = dot(sub(p, c), axis);
            s += t * t * t;
        }
        return s;
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    static double[] scale(double[] a, double s) {
        return new double[]{a[0] * s, a[1] * s, a[2] * s};
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
