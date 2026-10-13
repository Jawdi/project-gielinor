package gielinor.builder;

import java.util.ArrayList;
import java.util.List;

/**
 * Fits a creature's model onto one of Zomboid's animals: its box stretched
 * onto the animal's mesh along each axis (both games face their models along
 * -z, so a head lands on a head), then every vertex bound to the nearest
 * bone, blended across joints. The skeleton is the animal's own, so Zomboid's
 * animal animations move it.
 */
final class AnimalFit {
    /** How far either side of a joint vertices are shared between its two bones. */
    private static final double BLEND = 0.04;

    private record Segment(String bone, double[] a, double[] b) {
    }

    private final List<Segment> segments = new ArrayList<>();
    private final double[] low = new double[3];
    private final double[] high = new double[3];

    AnimalFit(Skeleton skeleton) {
        // A bone runs from its own joint to each skinned child's; a bone with
        // no skinned child is just its joint.
        for (String bone : skeleton.offsets.keySet()) {
            boolean child = false;
            for (String other : skeleton.offsets.keySet()) {
                if (bone.equals(skeleton.parents.get(other))) {
                    segments.add(new Segment(bone, skeleton.bind(bone), skeleton.bind(other)));
                    child = true;
                }
            }
            if (!child) {
                segments.add(new Segment(bone, skeleton.bind(bone), skeleton.bind(bone)));
            }
        }
        for (int k = 0; k < 3; k++) {
            low[k] = Double.MAX_VALUE;
            high[k] = -Double.MAX_VALUE;
        }
        for (double[] v : skeleton.bodyVertices) {
            for (int k = 0; k < 3; k++) {
                low[k] = Math.min(low[k], v[k]);
                high[k] = Math.max(high[k], v[k]);
            }
        }
    }

    /** Fits a model's vertices (game units, y down) and binds them. */
    BodyFit.Bound[] fit(List<double[]> vertices) {
        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        List<double[]> up = new ArrayList<>();
        for (double[] v : vertices) {
            double[] p = {v[0], -v[1], v[2]};
            up.add(p);
            for (int k = 0; k < 3; k++) {
                lo[k] = Math.min(lo[k], p[k]);
                hi[k] = Math.max(hi[k], p[k]);
            }
        }
        BodyFit.Bound[] out = new BodyFit.Bound[up.size()];
        for (int i = 0; i < up.size(); i++) {
            double[] p = new double[3];
            for (int k = 0; k < 3; k++) {
                double span = hi[k] - lo[k];
                double t = span == 0 ? 0.5 : (up.get(i)[k] - lo[k]) / span;
                p[k] = low[k] + t * (high[k] - low[k]);
            }
            out[i] = bind(p);
        }
        return out;
    }

    private BodyFit.Bound bind(double[] p) {
        Segment best = null;
        Segment next = null;
        double bestDistance = Double.MAX_VALUE;
        double nextDistance = Double.MAX_VALUE;
        for (Segment s : segments) {
            double d = distance(p, s.a, s.b);
            if (d < bestDistance) {
                next = best;
                nextDistance = bestDistance;
                best = s;
                bestDistance = d;
            } else if (d < nextDistance) {
                next = s;
                nextDistance = d;
            }
        }
        boolean joined = next != null && (close(best.a, next.b) || close(best.b, next.a) || close(best.a, next.a));
        double weight = joined ? Math.max(0, Math.min(0.5, 0.5 - (nextDistance - bestDistance) / (2 * BLEND))) : 0;
        return weight > 0 && !best.bone.equals(next.bone) ? new BodyFit.Bound(p, best.bone, next.bone, weight) : new BodyFit.Bound(p, best.bone, null, 0);
    }

    private static boolean close(double[] a, double[] b) {
        double[] d = ModelExporter.sub(a, b);
        return ModelExporter.dot(d, d) < 1e-8;
    }

    private static double distance(double[] p, double[] a, double[] b) {
        double[] ab = ModelExporter.sub(b, a);
        double[] ap = ModelExporter.sub(p, a);
        double len = ModelExporter.dot(ab, ab);
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ModelExporter.dot(ap, ab) / len));
        double[] d = ModelExporter.sub(p, ModelExporter.add(a, ModelExporter.scale(ab, t)));
        return Math.sqrt(ModelExporter.dot(d, d));
    }
}
