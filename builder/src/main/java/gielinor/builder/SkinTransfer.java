package gielinor.builder;

import java.util.ArrayList;
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

    private final Skeleton skeleton;
    /** The body's triangles to copy from. A body has under a few thousand, few enough to check every one. */
    private final List<Integer> faces = new ArrayList<>();
    /** Copies from the triangles of `skeleton`'s body whose main bone passes `region`. */
    SkinTransfer(Skeleton skeleton, Predicate<String> region) {
        this.skeleton = skeleton;
        for (int f = 0; f < skeleton.bodyFaces.size(); f++) {
            if (region.test(skeleton.mainBone(skeleton.bodyFaces.get(f)))) {
                faces.add(f);
            }
        }
    }

    /** Binds a fitted point with the weights at the nearest point on the body. */
    Bound bind(double[] p) {
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

    private record Nearest(int face, double[] bary) {
    }

    /** The body triangle nearest `p`, and the nearest point's weights over its corners. */
    private Nearest nearest(double[] p) {
        int bestFace = -1;
        double bestDistance = Double.MAX_VALUE;
        double[] bestBary = null;
        double[] bary = new double[3];
        for (int f : faces) {
            double d = closest(p, f, bary);
            if (d < bestDistance) {
                bestDistance = d;
                bestFace = f;
                bestBary = bary.clone();
            }
        }
        return bestFace >= 0 ? new Nearest(bestFace, bestBary) : null;
    }

    /** Keeps the strongest few bones and scales their weights to sum to one. */
    static Bound strongest(double[] p, Map<String, Double> weights) {
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
        return new Bound(p, bones, w);
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
}
