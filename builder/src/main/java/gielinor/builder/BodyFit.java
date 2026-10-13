package gielinor.builder;

import java.util.ArrayList;
import java.util.List;

/**
 * Fits worn models, which the game models on a player standing with arms
 * down, onto Zomboid's skeleton in its T-pose. Heights map band by band
 * between matching landmarks (ankle, knee, hip, shoulder, crown); arms swing
 * up about the shoulder to lie along the T-pose arm and stretch to its
 * length; widths and depths scale to Zomboid's body as measured at each
 * height. Every vertex is then bound to its nearest bone.
 *
 * Both games face their models along -z with the right side at -x, so no
 * axis is mirrored.
 */
final class BodyFit {
    /** Heights (up from the feet, game units) of the ankle, knee, hip, shoulder and crown of the game's player. */
    private static final double[] GAME_HEIGHTS = {0, 19, 66, 114, 163, 196};
    /** The same landmarks on Zomboid's T-pose skeleton. */
    private static final double[] ZOMBOID_HEIGHTS = {0, 0.066, 0.270, 0.505, 0.768, 0.98};
    /** Distances down the hanging arm from the shoulder: elbow, wrist, fingertips (game units). */
    private static final double[] GAME_ARM = {0, 25, 49, 72};
    /** The same points along Zomboid's T-pose arm, as distance from the body's middle. */
    private static final double[] ZOMBOID_ARM = {0.103, 0.238, 0.374, 0.49};
    /** The game's arms hang this far either side of the middle. */
    private static final double GAME_ARM_X = 27;
    /** Clothes sit a little proud of the body so it doesn't show through. */
    private static final double INFLATE = 1.06;
    private static final double BAND = 0.05;

    /** A vertex bound to a bone. */
    record Bound(double[] position, String bone) {
    }

    private record Segment(String bone, double[] a, double[] b, boolean arm) {
    }

    private final List<Segment> segments = new ArrayList<>();
    private final double[] widthRatio;
    private final double[] depthRatio;
    private final double[] zombieDepthMiddle;
    private final double[] gameDepthMiddle;
    private final double armRatio;

    /** `gameBody` is the default player's body (torso, legs, arms and so on) in game units. */
    BodyFit(Skeleton skeleton, List<double[]> gameBody) {
        for (String side : new String[]{"R", "L"}) {
            double s = side.equals("R") ? -1 : 1;
            segment(skeleton, "Bip01_" + side + "_UpperArm", "Bip01_" + side + "_Forearm", true);
            segment(skeleton, "Bip01_" + side + "_Forearm", "Bip01_" + side + "_Hand", true);
            segments.add(new Segment("Bip01_" + side + "_Hand", skeleton.bind("Bip01_" + side + "_Hand"), new double[]{s * 0.49, 0.71, 0.04}, true));
            segment(skeleton, "Bip01_" + side + "_Thigh", "Bip01_" + side + "_Calf", false);
            segment(skeleton, "Bip01_" + side + "_Calf", "Bip01_" + side + "_Foot", false);
            double[] foot = skeleton.bind("Bip01_" + side + "_Foot");
            segments.add(new Segment("Bip01_" + side + "_Foot", foot, new double[]{foot[0], 0.0, foot[2] - 0.12}, false));
        }
        double[] pelvis = skeleton.bind("Bip01_Pelvis");
        segments.add(new Segment("Bip01_Pelvis", new double[]{pelvis[0], pelvis[1] - 0.04, pelvis[2]}, skeleton.bind("Bip01_Spine"), false));
        segment(skeleton, "Bip01_Spine", "Bip01_Spine1", false);
        segment(skeleton, "Bip01_Spine1", "Bip01_Neck", false);
        segment(skeleton, "Bip01_Neck", "Bip01_Head", false);
        double[] head = skeleton.bind("Bip01_Head");
        segments.add(new Segment("Bip01_Head", head, new double[]{head[0], 0.98, head[2]}, false));

        // Body shape at each height: Zomboid's body (arms left out) against
        // the game's default player mapped to the same heights.
        int bands = (int) Math.ceil(1.0 / BAND) + 1;
        double[] zw = new double[bands];
        double[] zMin = filled(bands, Double.MAX_VALUE);
        double[] zMax = filled(bands, -Double.MAX_VALUE);
        for (double[] v : skeleton.bodyVertices) {
            if (Math.abs(v[0]) > 0.16) {
                continue;
            }
            int b = band(v[1]);
            zw[b] = Math.max(zw[b], Math.abs(v[0]));
            zMin[b] = Math.min(zMin[b], v[2]);
            zMax[b] = Math.max(zMax[b], v[2]);
        }
        double[] gw = new double[bands];
        double[] gMin = filled(bands, Double.MAX_VALUE);
        double[] gMax = filled(bands, -Double.MAX_VALUE);
        double gameArmThickness = 0;
        for (double[] v : gameBody) {
            double h = -v[1];
            if (isArm(v[0], h)) {
                gameArmThickness = Math.max(gameArmThickness, Math.abs(v[2]));
                continue;
            }
            int b = band(height(h));
            gw[b] = Math.max(gw[b], Math.abs(v[0]) * ModelExporter.SCALE);
            gMin[b] = Math.min(gMin[b], v[2] * ModelExporter.SCALE);
            gMax[b] = Math.max(gMax[b], v[2] * ModelExporter.SCALE);
        }
        widthRatio = new double[bands];
        depthRatio = new double[bands];
        zombieDepthMiddle = new double[bands];
        gameDepthMiddle = new double[bands];
        for (int b = 0; b < bands; b++) {
            boolean both = zw[b] > 0 && gw[b] > 0 && zMax[b] > zMin[b] && gMax[b] > gMin[b];
            widthRatio[b] = both ? clamp(zw[b] / gw[b]) : Double.NaN;
            depthRatio[b] = both ? clamp((zMax[b] - zMin[b]) / (gMax[b] - gMin[b])) : Double.NaN;
            zombieDepthMiddle[b] = both ? (zMax[b] + zMin[b]) / 2 : Double.NaN;
            gameDepthMiddle[b] = both ? (gMax[b] + gMin[b]) / 2 : Double.NaN;
        }
        fillGaps(widthRatio);
        fillGaps(depthRatio);
        fillGaps(zombieDepthMiddle);
        fillGaps(gameDepthMiddle);
        // Zomboid's arm is about 0.035 thick either side of its axis.
        armRatio = gameArmThickness > 0 ? clamp(0.035 / (gameArmThickness * ModelExporter.SCALE)) : 1;
    }

    private void segment(Skeleton skeleton, String from, String to, boolean arm) {
        segments.add(new Segment(from, skeleton.bind(from), skeleton.bind(to), arm));
    }

    /** Whether a point on the game's player (x, height up from the feet) is on an arm rather than the body. */
    static boolean isArm(double x, double height) {
        return Math.abs(x) > 21 && height > 80 && height < 168;
    }

    /**
     * Fits one worn-model vertex (game units, y down) onto the T-pose and
     * binds it. `armsAllowed` is false for things like capes, whose sides
     * aren't arms however wide they hang.
     */
    Bound fit(double x, double y, double z, boolean armsAllowed) {
        double h = -y;
        double[] p;
        boolean arm = armsAllowed && isArm(x, h);
        if (arm) {
            double side = Math.signum(x);
            double down = Math.max(0, GAME_HEIGHTS[4] - h);
            double out = interpolate(GAME_ARM, ZOMBOID_ARM, down);
            double across = (Math.abs(x) - GAME_ARM_X) * ModelExporter.SCALE * armRatio * INFLATE;
            p = new double[]{side * out, ZOMBOID_HEIGHTS[4] + across, z * ModelExporter.SCALE * armRatio * INFLATE + 0.04};
        } else {
            double zy = height(h);
            double w = sample(widthRatio, zy);
            double d = sample(depthRatio, zy);
            double gz = sample(gameDepthMiddle, zy);
            double zz = sample(zombieDepthMiddle, zy);
            p = new double[]{x * ModelExporter.SCALE * w * INFLATE, zy, zz + (z * ModelExporter.SCALE - gz) * d * INFLATE};
        }
        return new Bound(p, nearest(p, arm));
    }

    private String nearest(double[] p, boolean arm) {
        String best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Segment s : segments) {
            if (s.arm != arm) {
                continue;
            }
            double d = distance(p, s.a, s.b);
            if (d < bestDistance) {
                bestDistance = d;
                best = s.bone;
            }
        }
        return best;
    }

    /** A height on the game's player as a height on Zomboid's T-pose. */
    static double height(double h) {
        return interpolate(GAME_HEIGHTS, ZOMBOID_HEIGHTS, h);
    }

    static double interpolate(double[] from, double[] to, double v) {
        int last = from.length - 1;
        if (v <= from[0]) {
            return to[0] + (v - from[0]) * (to[1] - to[0]) / (from[1] - from[0]);
        }
        for (int i = 0; i < last; i++) {
            if (v <= from[i + 1]) {
                return to[i] + (v - from[i]) * (to[i + 1] - to[i]) / (from[i + 1] - from[i]);
            }
        }
        return to[last] + (v - from[last]) * (to[last] - to[last - 1]) / (from[last] - from[last - 1]);
    }

    private static int band(double y) {
        return (int) Math.max(0, Math.min(Math.round(y / BAND), Math.ceil(1.0 / BAND)));
    }

    private static double sample(double[] table, double y) {
        double f = Math.max(0, Math.min(y / BAND, table.length - 1));
        int i = (int) Math.floor(f);
        int j = Math.min(i + 1, table.length - 1);
        return table[i] + (table[j] - table[i]) * (f - i);
    }

    private static double clamp(double ratio) {
        return Math.max(0.6, Math.min(2.0, ratio));
    }

    private static double[] filled(int n, double v) {
        double[] out = new double[n];
        java.util.Arrays.fill(out, v);
        return out;
    }

    /** Fills bands with no measurement from their nearest measured neighbour. */
    private static void fillGaps(double[] t) {
        for (int pass = 0; pass < t.length; pass++) {
            for (int i = 0; i < t.length; i++) {
                if (Double.isNaN(t[i])) {
                    if (i > 0 && !Double.isNaN(t[i - 1])) {
                        t[i] = t[i - 1];
                    } else if (i + 1 < t.length && !Double.isNaN(t[i + 1])) {
                        t[i] = t[i + 1];
                    }
                }
            }
        }
        for (int i = 0; i < t.length; i++) {
            if (Double.isNaN(t[i])) {
                t[i] = 1;
            }
        }
    }

    private static double distance(double[] p, double[] a, double[] b) {
        double[] ab = ModelExporter.sub(b, a);
        double[] ap = ModelExporter.sub(p, a);
        double len = ModelExporter.dot(ab, ab);
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ModelExporter.dot(ap, ab) / len));
        double[] q = ModelExporter.add(a, ModelExporter.scale(ab, t));
        double[] d = ModelExporter.sub(p, q);
        return Math.sqrt(ModelExporter.dot(d, d));
    }
}
