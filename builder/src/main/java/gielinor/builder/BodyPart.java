package gielinor.builder;

import java.util.Set;

/**
 * A part of Zomboid's body: the torso, the head, an arm or a leg. A fitted
 * person's vertex belongs to the part its animation group goes with (the
 * game's models say which group each vertex is in: an upper arm, a shin), and
 * takes its weights and its place on the body's texture from that part of
 * Zomboid's body alone. Looked for over the whole body, a point inside
 * Zomboid's (the game's limbs are thinner, its chests further out or in)
 * could land on another part: a chest on a thigh.
 */
enum BodyPart {
    TORSO(new String[]{"Pelvis", "Spine", "Spine1", "Neck"},
        Set.of("Pelvis", "Spine", "Spine1", "Neck", "L_Clavicle", "R_Clavicle")),
    HEAD(new String[]{"Neck", "Head"}, Set.of("Neck", "Head")),
    LEFT_ARM(new String[]{"L_UpperArm", "L_Forearm", "L_Hand", "L_Finger1"},
        Set.of("L_Clavicle", "L_UpperArm", "L_Forearm", "L_Hand", "L_Finger0", "L_Finger1")),
    RIGHT_ARM(new String[]{"R_UpperArm", "R_Forearm", "R_Hand", "R_Finger1"},
        Set.of("R_Clavicle", "R_UpperArm", "R_Forearm", "R_Hand", "R_Finger0", "R_Finger1")),
    LEFT_LEG(new String[]{"L_Thigh", "L_Calf", "L_Foot", "L_Toe0"}, Set.of("Pelvis", "L_Thigh", "L_Calf", "L_Foot", "L_Toe0")),
    RIGHT_LEG(new String[]{"R_Thigh", "R_Calf", "R_Foot", "R_Toe0"}, Set.of("Pelvis", "R_Thigh", "R_Calf", "R_Foot", "R_Toe0"));

    /** The joints down the middle of the part, in order: lines out from them reach its skin. */
    private final String[] axis;
    /** The bones moving the part's skin most (without "Bip01_"). */
    private final Set<String> skin;

    BodyPart(String[] axis, Set<String> skin) {
        this.axis = axis;
        this.skin = skin;
    }

    /** The joints down the middle of the part, as bone names. */
    String[] axis() {
        String[] out = new String[axis.length];
        for (int i = 0; i < axis.length; i++) {
            out[i] = "Bip01_" + axis[i];
        }
        return out;
    }

    /** Whether a triangle of Zomboid's body moved most by `bone` is this part's skin. */
    boolean skins(String bone) {
        return bone.startsWith("Bip01_") && skin.contains(bone.substring("Bip01_".length()));
    }

    /** The part a bone moves. */
    static BodyPart of(String bone) {
        boolean left = bone.contains("_L_");
        boolean right = bone.contains("_R_");
        if (bone.contains("Clavicle") || bone.contains("UpperArm") || bone.contains("Forearm") || bone.contains("Hand")
                || bone.contains("Finger")) {
            return left ? LEFT_ARM : RIGHT_ARM;
        }
        if (left || right) {
            return left ? LEFT_LEG : RIGHT_LEG;
        }
        return bone.endsWith("Head") || bone.endsWith("Neck") ? HEAD : TORSO;
    }
}
