package gielinor.builder;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * A garment's texture laid out as Zomboid's body texture is, as Zomboid's
 * own clothes are: Zomboid paints blood, dirt and holes onto clothes through
 * masks in that layout, so laid out the same way they splatter over the
 * garment as on its own.
 *
 * Each corner of the garment takes the texture coordinates of the point on
 * Zomboid's body straight out from it (from the bone it lies along), so
 * faces sharing a corner share it in the texture and
 * the masks run on across them. A face whose corners land on separate pieces
 * of the body's texture (across a seam) would stretch over the texture
 * between; it's laid onto the one body triangle nearest it instead.
 *
 * The texture is then painted: each face's triangle in its colour, larger
 * faces first so small details (a belt, a button) show over them.
 */
final class GarmentLayout {
    /** The texture's size, as Zomboid's body texture and its masks are. */
    static final int SIZE = 256;
    /** A face stretched over the texture more than this, compared with its size, crosses a seam. */
    private static final double SEAM_STRETCH = 4;

    private final Skeleton body;
    private final Mesh mesh;
    /** The bones moving Zomboid's body (with a parent to run from). */
    private final List<String> bones = new ArrayList<>();
    /** Each face's corners' texture coordinates. */
    private final double[][][] uvs;

    GarmentLayout(Skeleton body, Mesh mesh) {
        this.body = body;
        this.mesh = mesh;
        for (Map<String, Double> weights : body.bodyWeights) {
            for (String bone : weights.keySet()) {
                if (!bones.contains(bone) && body.bindPositions.containsKey(body.parents.get(bone))) {
                    bones.add(bone);
                }
            }
        }
        int n = mesh.faces.size();
        double[][] vertexUv = new double[mesh.vertices.size()][];
        for (int[] face : mesh.faces) {
            for (int v : face) {
                if (vertexUv[v] == null) {
                    vertexUv[v] = surfaceUv(mesh.vertices.get(v));
                }
            }
        }
        double scale = typicalScale();
        uvs = new double[n][][];
        for (int f = 0; f < n; f++) {
            int[] face = mesh.faces.get(f);
            double[][] corners = {vertexUv[face[0]], vertexUv[face[1]], vertexUv[face[2]]};
            if (longestEdge(corners) > SEAM_STRETCH * scale * longestEdge3d(face) + 4.0 / SIZE) {
                corners = projected(face);
            }
            uvs[f] = corners;
        }
    }

    double[] uv(int face, int corner) {
        return uvs[face][corner];
    }

    /** Texture units per metre on Zomboid's body, at its median triangle. */
    private double typicalScale() {
        List<Double> ratios = new ArrayList<>();
        for (int[] f : body.bodyFaces) {
            double[][] uv = {body.bodyUvs.get(f[0]), body.bodyUvs.get(f[1]), body.bodyUvs.get(f[2])};
            double geometric = 0;
            double texture = longestEdge(uv);
            for (int k = 0; k < 3; k++) {
                geometric = Math.max(geometric, distance(body.bodyVertices.get(f[k]), body.bodyVertices.get(f[(k + 1) % 3])));
            }
            if (geometric > 1e-6) {
                ratios.add(texture / geometric);
            }
        }
        ratios.sort(Double::compare);
        return ratios.isEmpty() ? 1 : ratios.get(ratios.size() / 2);
    }

    private double longestEdge3d(int[] face) {
        double longest = 0;
        for (int k = 0; k < 3; k++) {
            longest = Math.max(longest, distance(mesh.vertices.get(face[k]), mesh.vertices.get(face[(k + 1) % 3])));
        }
        return longest;
    }

    private static double longestEdge(double[][] uv) {
        double longest = 0;
        for (int k = 0; k < 3; k++) {
            double du = uv[k][0] - uv[(k + 1) % 3][0];
            double dv = uv[k][1] - uv[(k + 1) % 3][1];
            longest = Math.max(longest, Math.sqrt(du * du + dv * dv));
        }
        return longest;
    }

    /**
     * The texture coordinates of the point on Zomboid's body straight out
     * from the bone `p` lies along, through `p`. The game's limbs are thinner
     * than Zomboid's, so a garment sits inside Zomboid's body; the nearest
     * point on it would crowd a whole trouser leg onto the side of the
     * body's leg it lies nearest, and blood would fall on it in streaks.
     * Straight out from the bone, the garment wraps the body's limb as far
     * round as it wraps its own.
     */
    private double[] surfaceUv(double[] p) {
        double[] axis = nearestOnBones(p);
        if (axis == null) {
            return nearestUv(p);
        }
        double[] out = ModelExporter.sub(p, axis);
        if (ModelExporter.dot(out, out) < 1e-8) {
            return nearestUv(p);
        }
        double best = Double.MAX_VALUE;
        double[] uv = null;
        for (int[] f : body.bodyFaces) {
            double[] hit = rayHit(axis, out, f);
            if (hit != null && hit[3] < best) {
                best = hit[3];
                uv = uvAt(f, new double[]{hit[0], hit[1], hit[2]});
            }
        }
        return uv != null ? uv : nearestUv(p);
    }

    /** The point nearest `p` on the bones Zomboid's body hangs from, each a segment from its parent. */
    private double[] nearestOnBones(double[] p) {
        double best = Double.MAX_VALUE;
        double[] out = null;
        for (String bone : bones) {
            double[] a = body.bindPositions.get(body.parents.get(bone));
            double[] b = body.bindPositions.get(bone);
            double[] ab = ModelExporter.sub(b, a);
            double len = ModelExporter.dot(ab, ab);
            double t = len == 0 ? 0 : Math.max(0, Math.min(1, ModelExporter.dot(ModelExporter.sub(p, a), ab) / len));
            double[] q = ModelExporter.add(a, ModelExporter.scale(ab, t));
            double d = distance(p, q);
            if (d < best) {
                best = d;
                out = q;
            }
        }
        return out;
    }

    /** Where the ray from `from` along `dir` (forwards only) meets a body triangle: its weights and the distance along, or null. */
    private double[] rayHit(double[] from, double[] dir, int[] f) {
        double[] a = body.bodyVertices.get(f[0]);
        double[] e1 = ModelExporter.sub(body.bodyVertices.get(f[1]), a);
        double[] e2 = ModelExporter.sub(body.bodyVertices.get(f[2]), a);
        double[] h = cross(dir, e2);
        double det = ModelExporter.dot(e1, h);
        if (Math.abs(det) < 1e-12) {
            return null;
        }
        double[] s = ModelExporter.sub(from, a);
        double u = ModelExporter.dot(s, h) / det;
        if (u < 0 || u > 1) {
            return null;
        }
        double[] q = cross(s, e1);
        double v = ModelExporter.dot(dir, q) / det;
        if (v < 0 || u + v > 1) {
            return null;
        }
        double t = ModelExporter.dot(e2, q) / det;
        return t > 0 ? new double[]{1 - u - v, u, v, t} : null;
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    /** The texture coordinates of the point on Zomboid's body nearest `p`. */
    private double[] nearestUv(double[] p) {
        double best = Double.MAX_VALUE;
        double[] uv = {0, 0};
        for (int[] f : body.bodyFaces) {
            double[] w = closestWeights(p, f);
            double[] q = at(f, w);
            double d = distance(p, q);
            if (d < best) {
                best = d;
                uv = uvAt(f, w);
            }
        }
        return uv;
    }

    /** A face's corners laid onto the body triangle nearest its middle, by their weights in its plane. */
    private double[][] projected(int[] face) {
        double[] middle = new double[3];
        for (int v : face) {
            for (int k = 0; k < 3; k++) {
                middle[k] += mesh.vertices.get(v)[k] / 3;
            }
        }
        int[] nearest = null;
        double best = Double.MAX_VALUE;
        for (int[] f : body.bodyFaces) {
            double d = distance(middle, at(f, closestWeights(middle, f)));
            if (d < best) {
                best = d;
                nearest = f;
            }
        }
        double[][] out = new double[3][];
        for (int k = 0; k < 3; k++) {
            out[k] = clamp(uvAt(nearest, planeWeights(mesh.vertices.get(face[k]), nearest)));
        }
        return out;
    }

    private static double[] clamp(double[] uv) {
        return new double[]{Math.max(0, Math.min(1, uv[0])), Math.max(0, Math.min(1, uv[1]))};
    }

    private double[] at(int[] f, double[] w) {
        double[] out = new double[3];
        for (int k = 0; k < 3; k++) {
            for (int c = 0; c < 3; c++) {
                out[c] += body.bodyVertices.get(f[k])[c] * w[k];
            }
        }
        return out;
    }

    private double[] uvAt(int[] f, double[] w) {
        double[] out = new double[2];
        for (int k = 0; k < 3; k++) {
            out[0] += body.bodyUvs.get(f[k])[0] * w[k];
            out[1] += body.bodyUvs.get(f[k])[1] * w[k];
        }
        return out;
    }

    /** The weights of a point projected onto a triangle's plane (not kept inside it). */
    private double[] planeWeights(double[] p, int[] f) {
        double[] a = body.bodyVertices.get(f[0]);
        double[] b = body.bodyVertices.get(f[1]);
        double[] c = body.bodyVertices.get(f[2]);
        double[] v0 = ModelExporter.sub(b, a);
        double[] v1 = ModelExporter.sub(c, a);
        double[] v2 = ModelExporter.sub(p, a);
        double d00 = ModelExporter.dot(v0, v0);
        double d01 = ModelExporter.dot(v0, v1);
        double d11 = ModelExporter.dot(v1, v1);
        double d20 = ModelExporter.dot(v2, v0);
        double d21 = ModelExporter.dot(v2, v1);
        double denom = d00 * d11 - d01 * d01;
        if (Math.abs(denom) < 1e-12) {
            return new double[]{1, 0, 0};
        }
        double v = (d11 * d20 - d01 * d21) / denom;
        double w = (d00 * d21 - d01 * d20) / denom;
        return new double[]{1 - v - w, v, w};
    }

    /** The weights of the point of a triangle nearest `p`. */
    private double[] closestWeights(double[] p, int[] f) {
        double[] w = planeWeights(p, f);
        if (w[0] >= 0 && w[1] >= 0 && w[2] >= 0) {
            return w;
        }
        // Outside: the nearest point on one of its edges.
        double best = Double.MAX_VALUE;
        double[] out = {1, 0, 0};
        for (int k = 0; k < 3; k++) {
            double[] a = body.bodyVertices.get(f[k]);
            double[] b = body.bodyVertices.get(f[(k + 1) % 3]);
            double[] ab = ModelExporter.sub(b, a);
            double len = ModelExporter.dot(ab, ab);
            double t = len == 0 ? 0 : Math.max(0, Math.min(1, ModelExporter.dot(ModelExporter.sub(p, a), ab) / len));
            double[] q = ModelExporter.add(a, ModelExporter.scale(ab, t));
            double d = distance(p, q);
            if (d < best) {
                best = d;
                out = new double[3];
                out[k] = 1 - t;
                out[(k + 1) % 3] = t;
            }
        }
        return out;
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Paints the garment's texture: every face's triangle in its colour
     * (`colourOf` a face, as RGB), larger faces first, after a ground of the
     * colour covering the most of it so the faces' edges never meet bare
     * texture.
     */
    void paint(Path png, java.util.function.IntUnaryOperator colourOf) throws IOException {
        int n = mesh.faces.size();
        Integer[] order = new Integer[n];
        double[] area = new double[n];
        java.util.Map<Integer, Double> coverByColour = new java.util.HashMap<>();
        for (int f = 0; f < n; f++) {
            order[f] = f;
            double[][] t = uvs[f];
            area[f] = Math.abs((t[1][0] - t[0][0]) * (t[2][1] - t[0][1]) - (t[2][0] - t[0][0]) * (t[1][1] - t[0][1])) / 2;
            coverByColour.merge(colourOf.applyAsInt(f), area[f], Double::sum);
        }
        java.util.Arrays.sort(order, Comparator.comparingDouble(f -> -area[f]));
        int ground = coverByColour.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).map(java.util.Map.Entry::getKey).orElse(0);
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                image.setRGB(x, y, ground);
            }
        }
        for (int f : order) {
            fill(image, uvs[f], colourOf.applyAsInt(f));
        }
        ImageIO.write(image, "png", png.toFile());
    }

    /** Fills a triangle given in texture coordinates, a pixel wider all round so filtering finds its colour. */
    private static void fill(BufferedImage image, double[][] t, int rgb) {
        double[][] p = new double[3][2];
        for (int k = 0; k < 3; k++) {
            p[k][0] = t[k][0] * SIZE;
            p[k][1] = t[k][1] * SIZE;
        }
        int x0 = (int) Math.max(0, Math.floor(Math.min(p[0][0], Math.min(p[1][0], p[2][0]))) - 1);
        int x1 = (int) Math.min(SIZE - 1, Math.ceil(Math.max(p[0][0], Math.max(p[1][0], p[2][0]))) + 1);
        int y0 = (int) Math.max(0, Math.floor(Math.min(p[0][1], Math.min(p[1][1], p[2][1]))) - 1);
        int y1 = (int) Math.min(SIZE - 1, Math.ceil(Math.max(p[0][1], Math.max(p[1][1], p[2][1]))) + 1);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (near(p, x + 0.5, y + 0.5, 1.0)) {
                    image.setRGB(x, y, rgb);
                }
            }
        }
    }

    /** Whether a point is inside a triangle or within `margin` pixels of it. */
    private static boolean near(double[][] p, double x, double y, double margin) {
        double area = (p[1][0] - p[0][0]) * (p[2][1] - p[0][1]) - (p[2][0] - p[0][0]) * (p[1][1] - p[0][1]);
        if (Math.abs(area) < 1e-9) {
            return false;
        }
        double sign = Math.signum(area);
        for (int k = 0; k < 3; k++) {
            double[] a = p[k];
            double[] b = p[(k + 1) % 3];
            double ex = b[0] - a[0];
            double ey = b[1] - a[1];
            double len = Math.sqrt(ex * ex + ey * ey);
            double side = sign * (ex * (y - a[1]) - ey * (x - a[0])) / Math.max(len, 1e-9);
            if (side < -margin) {
                return false;
            }
        }
        return true;
    }
}
