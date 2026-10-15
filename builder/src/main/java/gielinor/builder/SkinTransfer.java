package gielinor.builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Binds fitted vertices to bones by copying the weights of Zomboid's own
 * body: each vertex takes the weights at the nearest point on the body's
 * surface, blended across that triangle's corners, so a fitted piece bends
 * exactly as the skin under it does. This is the weight transfer modellers
 * do by hand when they fit clothing to Zomboid's characters.
 */
final class SkinTransfer {
    /** Zomboid's skins move a vertex with at most this many bones. */
    static final int MAX_BONES = 4;
    /** Bones whose weights are too slight to matter are dropped. */
    private static final double MIN_WEIGHT = 0.02;
    private static final double CELL = 0.04;

    private final Skeleton skeleton;
    /** The body's triangles in a grid of cells, each cell listing the triangles whose bounds reach it. */
    private final Map<Long, List<Integer>> grid = new HashMap<>();
    private final double[] low = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
    private final double[] high = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
    private final int[] cells = new int[3];
    /** Each body vertex's outward normal, averaged over the triangles around it. */
    private final double[][] normals;

    /** Copies from the triangles of `skeleton`'s body whose main bone passes `region`. */
    SkinTransfer(Skeleton skeleton, Predicate<String> region) {
        this.skeleton = skeleton;
        Mesh body = new Mesh();
        body.vertices.addAll(skeleton.bodyVertices);
        body.faces.addAll(skeleton.bodyFaces);
        // Most of a body's faces point away from its middle; turn the normals
        // round if its winding says otherwise.
        double sign = body.outwardVote() >= 0 ? 1 : -1;
        normals = new double[body.vertices.size()][3];
        for (int f = 0; f < body.faces.size(); f++) {
            double[] n = body.normal(f);
            for (int corner : body.faces.get(f)) {
                for (int k = 0; k < 3; k++) {
                    normals[corner][k] += sign * n[k];
                }
            }
        }
        for (double[] v : skeleton.bodyVertices) {
            for (int k = 0; k < 3; k++) {
                low[k] = Math.min(low[k], v[k]);
                high[k] = Math.max(high[k], v[k]);
            }
        }
        for (int k = 0; k < 3; k++) {
            cells[k] = (int) Math.ceil((high[k] - low[k]) / CELL) + 1;
        }
        List<int[]> faces = skeleton.bodyFaces;
        for (int f = 0; f < faces.size(); f++) {
            if (!region.test(mainBone(faces.get(f)))) {
                continue;
            }
            int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
            int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (int corner : faces.get(f)) {
                double[] v = skeleton.bodyVertices.get(corner);
                for (int k = 0; k < 3; k++) {
                    lo[k] = Math.min(lo[k], cell(v[k], k));
                    hi[k] = Math.max(hi[k], cell(v[k], k));
                }
            }
            for (int x = lo[0]; x <= hi[0]; x++) {
                for (int y = lo[1]; y <= hi[1]; y++) {
                    for (int z = lo[2]; z <= hi[2]; z++) {
                        grid.computeIfAbsent(key(x, y, z), k -> new ArrayList<>()).add(f);
                    }
                }
            }
        }
    }

    /** The bone moving a triangle most, summed over its corners. */
    private String mainBone(int[] face) {
        Map<String, Double> sum = new HashMap<>();
        for (int corner : face) {
            skeleton.bodyWeights.get(corner).forEach((bone, w) -> sum.merge(bone, w, Double::sum));
        }
        String best = null;
        for (Map.Entry<String, Double> e : sum.entrySet()) {
            if (best == null || e.getValue() > sum.get(best)) {
                best = e.getKey();
            }
        }
        return best == null ? "" : best;
    }

    /** Binds a fitted point with the weights at the nearest point on the body. */
    BodyFit.Bound bind(double[] p) {
        Nearest near = nearest(p);
        Map<String, Double> weights = new LinkedHashMap<>();
        if (near != null) {
            int[] face = skeleton.bodyFaces.get(near.face);
            for (int k = 0; k < 3; k++) {
                double share = near.bary[k];
                skeleton.bodyWeights.get(face[k]).forEach((bone, w) -> weights.merge(bone, w * share, Double::sum));
            }
        }
        return strongest(p, weights);
    }

    /**
     * `p` moved out from the body, if need be, so it sits at least `gap`
     * above the body's surface: a piece fitted to the body covers it rather
     * than sinking into it.
     */
    double[] outside(double[] p, double gap) {
        Nearest near = nearest(p);
        if (near == null) {
            return p;
        }
        int[] face = skeleton.bodyFaces.get(near.face);
        double[] q = new double[3];
        double[] n = new double[3];
        for (int k = 0; k < 3; k++) {
            double[] v = skeleton.bodyVertices.get(face[k]);
            double[] vn = normals[face[k]];
            for (int j = 0; j < 3; j++) {
                q[j] += v[j] * near.bary[k];
                n[j] += vn[j] * near.bary[k];
            }
        }
        double length = Math.sqrt(ModelExporter.dot(n, n));
        if (length == 0) {
            return p;
        }
        n = ModelExporter.scale(n, 1 / length);
        double height = ModelExporter.dot(ModelExporter.sub(p, q), n);
        return height >= gap ? p : ModelExporter.add(p, ModelExporter.scale(n, gap - height));
    }

    private record Nearest(int face, double[] bary) {
    }

    /** The body triangle nearest `p`, and the nearest point's weights over its corners. */
    private Nearest nearest(double[] p) {
        int[] c = {cell(p[0], 0), cell(p[1], 1), cell(p[2], 2)};
        int bestFace = -1;
        double bestDistance = Double.MAX_VALUE;
        double[] bestBary = null;
        int maxRing = 0;
        for (int k = 0; k < 3; k++) {
            maxRing = Math.max(maxRing, Math.abs(c[k]) + cells[k]);
        }
        for (int ring = 0; ring <= maxRing; ring++) {
            // Anything found within this ring is certainly nearer than all
            // triangles outside the next one.
            for (int x = c[0] - ring; x <= c[0] + ring; x++) {
                for (int y = c[1] - ring; y <= c[1] + ring; y++) {
                    for (int z = c[2] - ring; z <= c[2] + ring; z++) {
                        boolean shell = Math.abs(x - c[0]) == ring || Math.abs(y - c[1]) == ring || Math.abs(z - c[2]) == ring;
                        List<Integer> here = shell ? grid.get(key(x, y, z)) : null;
                        if (here == null) {
                            continue;
                        }
                        for (int f : here) {
                            double[] bary = new double[3];
                            double d = closest(p, f, bary);
                            if (d < bestDistance) {
                                bestDistance = d;
                                bestFace = f;
                                bestBary = bary;
                            }
                        }
                    }
                }
            }
            if (bestFace >= 0 && Math.sqrt(bestDistance) <= ring * CELL) {
                break;
            }
        }
        return bestFace >= 0 ? new Nearest(bestFace, bestBary) : null;
    }

    /** Keeps the strongest few bones and scales their weights to sum to one. */
    static BodyFit.Bound strongest(double[] p, Map<String, Double> weights) {
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(weights.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<Map.Entry<String, Double>> kept = new ArrayList<>();
        double total = 0;
        for (Map.Entry<String, Double> e : sorted) {
            if (kept.size() == MAX_BONES || (!kept.isEmpty() && e.getValue() < MIN_WEIGHT)) {
                break;
            }
            kept.add(e);
            total += e.getValue();
        }
        String[] bones = new String[kept.size()];
        double[] w = new double[kept.size()];
        for (int i = 0; i < kept.size(); i++) {
            bones[i] = kept.get(i).getKey();
            w[i] = total > 0 ? kept.get(i).getValue() / total : 1.0 / kept.size();
        }
        return new BodyFit.Bound(p, bones, w);
    }

    /**
     * The squared distance from `p` to triangle `f`, filling `bary` with the
     * closest point's weights over the triangle's corners (Ericson, Real-Time
     * Collision Detection, 5.1.5).
     */
    private double closest(double[] p, int f, double[] bary) {
        int[] face = skeleton.bodyFaces.get(f);
        double[] a = skeleton.bodyVertices.get(face[0]);
        double[] b = skeleton.bodyVertices.get(face[1]);
        double[] c = skeleton.bodyVertices.get(face[2]);
        double[] ab = ModelExporter.sub(b, a);
        double[] ac = ModelExporter.sub(c, a);
        double[] ap = ModelExporter.sub(p, a);
        double d1 = ModelExporter.dot(ab, ap);
        double d2 = ModelExporter.dot(ac, ap);
        if (d1 <= 0 && d2 <= 0) {
            return set(bary, 1, 0, 0, p, a, b, c);
        }
        double[] bp = ModelExporter.sub(p, b);
        double d3 = ModelExporter.dot(ab, bp);
        double d4 = ModelExporter.dot(ac, bp);
        if (d3 >= 0 && d4 <= d3) {
            return set(bary, 0, 1, 0, p, a, b, c);
        }
        double vc = d1 * d4 - d3 * d2;
        if (vc <= 0 && d1 >= 0 && d3 <= 0) {
            double v = d1 / (d1 - d3);
            return set(bary, 1 - v, v, 0, p, a, b, c);
        }
        double[] cp = ModelExporter.sub(p, c);
        double d5 = ModelExporter.dot(ab, cp);
        double d6 = ModelExporter.dot(ac, cp);
        if (d6 >= 0 && d5 <= d6) {
            return set(bary, 0, 0, 1, p, a, b, c);
        }
        double vb = d5 * d2 - d1 * d6;
        if (vb <= 0 && d2 >= 0 && d6 <= 0) {
            double w = d2 / (d2 - d6);
            return set(bary, 1 - w, 0, w, p, a, b, c);
        }
        double va = d3 * d6 - d5 * d4;
        if (va <= 0 && d4 - d3 >= 0 && d5 - d6 >= 0) {
            double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            return set(bary, 0, 1 - w, w, p, a, b, c);
        }
        double denom = va + vb + vc;
        if (denom == 0) {
            return set(bary, 1, 0, 0, p, a, b, c);
        }
        double v = vb / denom;
        double w = vc / denom;
        return set(bary, 1 - v - w, v, w, p, a, b, c);
    }

    private static double set(double[] bary, double u, double v, double w, double[] p, double[] a, double[] b, double[] c) {
        bary[0] = u;
        bary[1] = v;
        bary[2] = w;
        double[] d = new double[3];
        for (int k = 0; k < 3; k++) {
            d[k] = p[k] - (a[k] * u + b[k] * v + c[k] * w);
        }
        return ModelExporter.dot(d, d);
    }

    private int cell(double v, int axis) {
        return (int) Math.floor((v - low[axis]) / CELL);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + 1024) << 40) | ((long) (y + 1024) << 20) | (z + 1024);
    }
}
