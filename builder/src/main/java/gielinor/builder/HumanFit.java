package gielinor.builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts the game's people (the player's body and clothes, zombies, goblins
 * and the like) on Zomboid's skeleton the way the game itself animates them:
 * each of a model's animation groups (an upper arm, a shin, a head) moves
 * rigidly with one bone. The model keeps its own shape, scaled to Zomboid's
 * height; only its limbs turn, the arms about the shoulders from however the
 * model holds them into Zomboid's T-pose, and the legs about the hips from
 * straight down into the splay of Zomboid's bind pose.
 *
 * Both games face their models along -z with the right side at -x, so no
 * axis is mirrored.
 */
final class HumanFit {
    /** Zomboid's characters are this tall. */
    static final double HEIGHT = 0.98;
    /** The player's crown, in game units up from the feet. */
    static final double PLAYER_CROWN = 196;

    /** A bone as the line from its joint to its child's, in the T-pose. */
    private record Bone(String name, double[] from, double[] to) {
    }

    private final List<Bone> bones = new ArrayList<>();
    private final Skeleton skeleton;

    HumanFit(Skeleton skeleton) {
        this.skeleton = skeleton;
        bone("Bip01_Pelvis", "Bip01_Spine");
        bone("Bip01_Spine", "Bip01_Spine1");
        bone("Bip01_Spine1", "Bip01_Neck");
        bone("Bip01_Neck", "Bip01_Head");
        double[] head = skeleton.bind("Bip01_Head");
        bones.add(new Bone("Bip01_Head", head, ModelExporter.add(head, new double[]{0, 0.14, 0})));
        for (String side : new String[]{"L", "R"}) {
            String p = "Bip01_" + side + "_";
            bone(p + "UpperArm", p + "Forearm");
            bone(p + "Forearm", p + "Hand");
            double[] hand = skeleton.bind(p + "Hand");
            double[] along = unit(ModelExporter.sub(hand, skeleton.bind(p + "Forearm")));
            bones.add(new Bone(p + "Hand", hand, ModelExporter.add(hand, ModelExporter.scale(along, 0.1))));
            bone(p + "Thigh", p + "Calf");
            bone(p + "Calf", p + "Foot");
            double[] foot = skeleton.bind(p + "Foot");
            bones.add(new Bone(p + "Foot", foot, ModelExporter.add(foot, new double[]{0, -0.06, -0.12})));
        }
    }

    private void bone(String name, String child) {
        bones.add(new Bone(name, skeleton.bind(name), skeleton.bind(child)));
    }

    /**
     * Fits a model, given in game units (y down), and returns each vertex's
     * position and bone. `crown` is the model's height in game units, which
     * becomes Zomboid's. `findArms` reads where the model holds its arms; if
     * false they are taken to hang at its sides, as the player's do (its body
     * and clothes must all agree, or a sleeve would part from its arm).
     * `armsAllowed` is false for pieces like capes, whose sides aren't arms.
     */
    Bound[] fit(Mesh mesh, double crown, boolean findArms, boolean armsAllowed) {
        double s = HEIGHT / crown;
        List<double[]> points = new ArrayList<>();
        for (double[] v : mesh.vertices) {
            points.add(new double[]{v[0] * s, -v[1] * s, v[2] * s});
        }
        // Each limb turns about its joint with the body from the model's pose
        // to the bind pose: `turns` holds the turn from the bind pose to the
        // model's. Arms go from the T-pose to however the model holds them;
        // legs from Zomboid's, which splay out to the feet, to the game's,
        // which hang straight down. Left as they are, the game's legs sit
        // inside Zomboid's, and cross as Zomboid's walk swings them in.
        Map<String, double[][]> turns = new HashMap<>();
        for (String side : new String[]{"L", "R"}) {
            double[] shoulder = skeleton.bind("Bip01_" + side + "_UpperArm");
            double sign = Math.signum(shoulder[0]);
            double[] tpose = unit(ModelExporter.sub(skeleton.bind("Bip01_" + side + "_Forearm"), shoulder));
            double[] hang = unit(new double[]{0.12 * sign, -1, 0});
            double[] target = findArms ? armDirection(points, shoulder, sign, hang) : hang;
            turns.put(side + "_Arm", rotation(tpose, target));
            double[] hip = skeleton.bind("Bip01_" + side + "_Thigh");
            double[] splay = unit(ModelExporter.sub(skeleton.bind("Bip01_" + side + "_Foot"), hip));
            turns.put(side + "_Leg", rotation(splay, new double[]{0, -1, 0}));
        }
        // The bones as the model stands: limbs turned to its pose.
        List<Bone> posed = new ArrayList<>();
        for (Bone b : bones) {
            if (armsAllowed || !isArm(b.name)) {
                String limb = limb(b.name);
                posed.add(limb == null ? b : new Bone(b.name,
                    turn(b.from, turns.get(limb), joint(limb)), turn(b.to, turns.get(limb), joint(limb))));
            }
        }
        // Each animation group goes with the bone nearest its middle; a
        // vertex with no group goes with the bone nearest itself.
        boolean grouped = mesh.groups.size() == points.size();
        Map<Integer, double[]> middles = new HashMap<>();
        if (grouped) {
            for (int i = 0; i < points.size(); i++) {
                double[] sum = middles.computeIfAbsent(mesh.groups.get(i), g -> new double[4]);
                for (int k = 0; k < 3; k++) {
                    sum[k] += points.get(i)[k];
                }
                sum[3]++;
            }
        }
        Map<Integer, String> groupBones = new HashMap<>();
        Bound[] out = new Bound[points.size()];
        for (int i = 0; i < points.size(); i++) {
            double[] p = points.get(i);
            int group = grouped ? mesh.groups.get(i) : -1;
            String bone = group >= 0
                ? groupBones.computeIfAbsent(group, g -> nearest(posed, middle(middles.get(g))))
                : nearest(posed, p);
            String limb = limb(bone);
            if (limb != null) {
                // Back from the model's pose into the bind pose.
                p = turn(p, transpose(turns.get(limb)), joint(limb));
            }
            // How a vertex shares its joints goes by where it was, before
            // the hands move to the grip, so the wrist bends as one.
            Bound shared = blendAtJoints(p, bone);
            if (bone.endsWith("_Hand") || bone.endsWith("_Forearm")) {
                p = toGrip(p, bone);
            }
            out[i] = new Bound(p, shared.bones(), shared.weights());
            mesh.vertices.set(i, p);
        }
        return out;
    }

    /**
     * Each bone's parent, for the joints between them: where the two share
     * the vertices about the joint, as Zomboid's own meshes are weighted, so
     * the model bends there rather than parting.
     */
    private static final Map<String, String> PARENTS = Map.ofEntries(
        Map.entry("Bip01_Spine", "Bip01_Pelvis"),
        Map.entry("Bip01_Spine1", "Bip01_Spine"),
        Map.entry("Bip01_Neck", "Bip01_Spine1"),
        Map.entry("Bip01_Head", "Bip01_Neck"),
        Map.entry("Bip01_L_UpperArm", "Bip01_Spine1"),
        Map.entry("Bip01_R_UpperArm", "Bip01_Spine1"),
        Map.entry("Bip01_L_Forearm", "Bip01_L_UpperArm"),
        Map.entry("Bip01_R_Forearm", "Bip01_R_UpperArm"),
        Map.entry("Bip01_L_Hand", "Bip01_L_Forearm"),
        Map.entry("Bip01_R_Hand", "Bip01_R_Forearm"),
        Map.entry("Bip01_L_Thigh", "Bip01_Pelvis"),
        Map.entry("Bip01_R_Thigh", "Bip01_Pelvis"),
        Map.entry("Bip01_L_Calf", "Bip01_L_Thigh"),
        Map.entry("Bip01_R_Calf", "Bip01_R_Thigh"),
        Map.entry("Bip01_L_Foot", "Bip01_L_Calf"),
        Map.entry("Bip01_R_Foot", "Bip01_R_Calf"));
    /**
     * How far each hand is moved (in metres, the right then the left) so the
     * game's fist closes where Zomboid holds things. Zomboid's animations keep
     * a held item's bone (Bip01_Prop1 in the right hand, Bip01_Prop2 in the
     * left) at a fixed point in the hand bone's frame, a few centimetres in
     * front of Zomboid's open palm, where its fingers curl round the handle.
     * The game's fists, fitted as they are, sit behind and above it, so
     * items hung below them. These carry the middle of each body type's
     * hands onto that point (right, then left): body type A's, B's, and
     * between the two for anyone else.
     */
    private static final double[][][] TO_GRIP = {
        {{-0.018, -0.039, 0.053}, {0.046, -0.061, 0.026}},
        {{-0.002, -0.015, 0.046}, {0.030, -0.037, 0.019}},
        {{-0.010, -0.027, 0.050}, {0.038, -0.049, 0.022}},
    };
    private double[][] toGrip = TO_GRIP[2];

    /** Fits what's fitted next to the player's body type (0 for A, 1 for B, -1 for anyone else). */
    void bodyType(int type) {
        toGrip = TO_GRIP[type >= 0 && type < 2 ? type : 2];
    }
    /** How far down the forearm (as a share of it) it starts curving towards the grip. */
    private static final double FOREARM_CURVE_FROM = 0.4;

    /**
     * A forearm or hand vertex moved so the hand closes on the grip: the
     * hand all the way, the forearm the more the nearer the wrist, easing in
     * from {@link #FOREARM_CURVE_FROM} so it curves into the hand rather
     * than kinking at the wrist. The elbow and wrist stay where Zomboid
     * bends them; moved off them, the arm folds in the wrong place as its
     * animations bend it.
     */
    private double[] toGrip(double[] p, String bone) {
        String side = bone.contains("_L_") ? "L" : "R";
        double[] shift = toGrip[side.equals("R") ? 0 : 1];
        double share = 1;
        if (bone.endsWith("_Forearm")) {
            double[] elbow = skeleton.bind("Bip01_" + side + "_Forearm");
            double[] forearm = ModelExporter.sub(skeleton.bind("Bip01_" + side + "_Hand"), elbow);
            double t = ModelExporter.dot(ModelExporter.sub(p, elbow), forearm) / ModelExporter.dot(forearm, forearm);
            double x = Math.max(0, Math.min(1, (t - FOREARM_CURVE_FROM) / (1 - FOREARM_CURVE_FROM)));
            share = x * x * (3 - 2 * x);
        }
        return ModelExporter.add(p, ModelExporter.scale(shift, share));
    }

    /** How far from a joint (in metres) its two bones share a vertex: half each at the joint, none at this distance. */
    private static final double JOINT_REACH = 0.04;

    /**
     * A vertex at `p` on `bone`, shared with the bone across any joint
     * within {@link #JOINT_REACH}: the joint where `bone` meets its parent,
     * and those where its children meet it.
     */
    private Bound blendAtJoints(double[] p, String bone) {
        if (bone.endsWith("_Hand") || bone.endsWith("_Forearm")) {
            Bound wrist = blendAtWrist(p, bone);
            if (wrist != null) {
                return wrist;
            }
        }
        List<String> bones = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double others = 0;
        for (Map.Entry<String, String> joint : PARENTS.entrySet()) {
            String child = joint.getKey();
            String parent = joint.getValue();
            String other = child.equals(bone) ? parent : parent.equals(bone) ? child : null;
            if (other == null) {
                continue;
            }
            double[] at = skeleton.bind(child);
            double[] d = ModelExporter.sub(p, at);
            double distance = Math.sqrt(ModelExporter.dot(d, d));
            if (distance < JOINT_REACH) {
                double w = 0.5 * (1 - distance / JOINT_REACH);
                bones.add(other);
                weights.add(w);
                others += w;
            }
        }
        if (others > 0.5) {
            // Near several joints at once: keep at least half on its own bone.
            for (int i = 0; i < weights.size(); i++) {
                weights.set(i, weights.get(i) * 0.5 / others);
            }
            others = 0.5;
        }
        bones.add(0, bone);
        weights.add(0, 1 - others);
        return new Bound(p, bones.toArray(new String[0]), weights.stream().mapToDouble(Double::doubleValue).toArray());
    }

    /** How far either side of the wrist (in metres, along the arm) the hand and forearm share a vertex. */
    private static final double WRIST_REACH = 0.03;

    /**
     * A vertex near a wrist, shared between hand and forearm by how far
     * along the arm it lies (half each at the wrist), or null if it isn't
     * near one. The hand sits off the wrist's axis where it holds things, so
     * the wrist bending swings it about; vertices either side of the seam
     * between the game's forearm and hand must share alike, or the seam
     * opens. By distance from the joint, they wouldn't.
     */
    private Bound blendAtWrist(double[] p, String bone) {
        String side = bone.contains("_L_") ? "L" : "R";
        String hand = "Bip01_" + side + "_Hand";
        String forearm = "Bip01_" + side + "_Forearm";
        double[] wrist = skeleton.bind(hand);
        double[] along = unit(ModelExporter.sub(wrist, skeleton.bind(forearm)));
        double d = ModelExporter.dot(ModelExporter.sub(p, wrist), along);
        if (Math.abs(d) >= WRIST_REACH) {
            return null;
        }
        double w = 0.5 + 0.5 * d / WRIST_REACH;
        return new Bound(p, new String[]{hand, forearm}, new double[]{w, 1 - w});
    }

    /**
     * Which way the model's arm on one side points from the shoulder: at its
     * hand, the points farthest out that side between hip and shoulder
     * height. Arms hanging close to the body read as hanging.
     */
    private static double[] armDirection(List<double[]> points, double[] shoulder, double sign, double[] hang) {
        List<double[]> side = new ArrayList<>();
        for (double[] p : points) {
            if (p[0] * sign > Math.abs(shoulder[0]) && p[1] > 0.35 && p[1] < shoulder[1]) {
                side.add(p);
            }
        }
        if (side.isEmpty()) {
            return hang;
        }
        side.sort((a, b) -> Double.compare(b[0] * sign, a[0] * sign));
        double[] hand = new double[3];
        int n = Math.max(1, side.size() / 10);
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < 3; k++) {
                hand[k] += side.get(i)[k] / n;
            }
        }
        double[] reach = ModelExporter.sub(hand, shoulder);
        boolean out = Math.sqrt(ModelExporter.dot(reach, reach)) > 0.15 && Math.abs(hand[0]) > Math.abs(shoulder[0]) + 0.08;
        return out ? unit(reach) : hang;
    }

    /** The limb a bone is part of ("L_Arm", "R_Leg"), or null for the body and head. */
    private static String limb(String bone) {
        String side = bone.contains("_L_") ? "L" : bone.contains("_R_") ? "R" : null;
        if (side == null) {
            return null;
        }
        return side + (isArm(bone) ? "_Arm" : "_Leg");
    }

    /** Where a limb joins the body: its shoulder or hip. */
    private double[] joint(String limb) {
        String side = limb.substring(0, 1);
        return skeleton.bind("Bip01_" + side + (limb.endsWith("Arm") ? "_UpperArm" : "_Thigh"));
    }

    private static boolean isArm(String bone) {
        return bone.contains("UpperArm") || bone.contains("Forearm") || bone.contains("Hand");
    }

    private static String nearest(List<Bone> bones, double[] p) {
        String best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Bone b : bones) {
            double d = distance(p, b.from, b.to);
            if (d < bestDistance) {
                bestDistance = d;
                best = b.name;
            }
        }
        return best;
    }

    private static double[] middle(double[] sum) {
        return new double[]{sum[0] / sum[3], sum[1] / sum[3], sum[2] / sum[3]};
    }

    private static double distance(double[] p, double[] a, double[] b) {
        double[] ab = ModelExporter.sub(b, a);
        double[] ap = ModelExporter.sub(p, a);
        double len = ModelExporter.dot(ab, ab);
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ModelExporter.dot(ap, ab) / len));
        double[] d = ModelExporter.sub(p, ModelExporter.add(a, ModelExporter.scale(ab, t)));
        return Math.sqrt(ModelExporter.dot(d, d));
    }

    private static double[] unit(double[] v) {
        double length = Math.sqrt(ModelExporter.dot(v, v));
        return length == 0 ? v : ModelExporter.scale(v, 1 / length);
    }

    /** The rotation turning unit vector `a` onto unit vector `b` (Rodrigues' formula), as a 3x3 matrix. */
    private static double[][] rotation(double[] a, double[] b) {
        double[] axis = ModelExporter.cross(a, b);
        double sin = Math.sqrt(ModelExporter.dot(axis, axis));
        double cos = ModelExporter.dot(a, b);
        if (sin < 1e-9) {
            return new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        }
        double[] k = ModelExporter.scale(axis, 1 / sin);
        double[][] kk = {{0, -k[2], k[1]}, {k[2], 0, -k[0]}, {-k[1], k[0], 0}};
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double k2 = 0;
                for (int m = 0; m < 3; m++) {
                    k2 += kk[i][m] * kk[m][j];
                }
                r[i][j] = (i == j ? 1 : 0) + sin * kk[i][j] + (1 - cos) * k2;
            }
        }
        return r;
    }

    private static double[][] transpose(double[][] m) {
        return new double[][]{{m[0][0], m[1][0], m[2][0]}, {m[0][1], m[1][1], m[2][1]}, {m[0][2], m[1][2], m[2][2]}};
    }

    /** `p` turned by `r` about `pivot`. */
    private static double[] turn(double[] p, double[][] r, double[] pivot) {
        double[] d = ModelExporter.sub(p, pivot);
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = pivot[i] + r[i][0] * d[0] + r[i][1] * d[1] + r[i][2] * d[2];
        }
        return out;
    }
}
