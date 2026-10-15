package gielinor.builder;

import java.util.ArrayList;
import java.util.List;

/**
 * Fits a creature's model onto one of Zomboid's animals: its box stretched
 * onto the animal's mesh along each axis (both games face their models along
 * -z, so a head lands on a head), then every vertex given the bone weights
 * of the animal's own skin under it. The skeleton is the animal's own, so
 * Zomboid's animal animations move it.
 */
final class AnimalFit {
    private final SkinTransfer skin;
    private final double[] low = new double[3];
    private final double[] high = new double[3];

    AnimalFit(Skeleton skeleton) {
        skin = new SkinTransfer(skeleton, bone -> true);
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
    Bound[] fit(List<double[]> vertices) {
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
        Bound[] out = new Bound[up.size()];
        for (int i = 0; i < up.size(); i++) {
            double[] p = new double[3];
            for (int k = 0; k < 3; k++) {
                double span = hi[k] - lo[k];
                double t = span == 0 ? 0.5 : (up.get(i)[k] - lo[k]) / span;
                p[k] = low[k] + t * (high[k] - low[k]);
            }
            out[i] = skin.bind(p);
        }
        return out;
    }
}
