package gielinor.builder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zomboid's character skeleton, read from its skinned male body
 * (`models_X/Skinned/MaleBody.x`). Skinned meshes are modelled in a T-pose;
 * each bone's `SkinWeights` block holds the inverse of its world matrix in
 * that pose (its offset), so a vertex `v` bound to bone `b` lands at
 * `v * offset(b) * world(b)` once the skeleton is posed.
 */
final class Skeleton {
    /** Everything in the body file before its mesh: templates and the bone frames, copied into every clothing mesh. */
    final String header;
    /** Each bone's offset matrix, row-major, in the order the body lists them. */
    final Map<String, double[]> offsets = new LinkedHashMap<>();
    /** Each bone's position in the T-pose. */
    final Map<String, double[]> bindPositions = new LinkedHashMap<>();
    /** The body's T-pose vertices, for measuring its shape. */
    final List<double[]> bodyVertices;
    /** The body's triangles, as indices into {@link #bodyVertices}. */
    final List<int[]> bodyFaces;
    /** How strongly each bone moves each body vertex, as the body's own skin has it. */
    final List<Map<String, Double>> bodyWeights = new ArrayList<>();
    /** Each frame's parent frame, from the frame hierarchy. */
    final Map<String, String> parents = new LinkedHashMap<>();

    private Skeleton(String header, Mesh body) {
        this.header = header;
        this.bodyVertices = body.vertices;
        this.bodyFaces = body.faces;
        for (int i = 0; i < body.vertices.size(); i++) {
            bodyWeights.add(new LinkedHashMap<>());
        }
    }

    static Skeleton read(Path maleBody) throws IOException {
        String text = Files.readString(maleBody, StandardCharsets.ISO_8859_1);
        Matcher mesh = Pattern.compile("\\n\\s*Mesh\\s+[^\\s{]*\\s*\\{").matcher(text);
        if (!mesh.find()) {
            throw new IOException("no mesh in " + maleBody);
        }
        // The mesh sits in a top-level frame of its own, before the bone frames
        // in some files and after them in others; the header is the file
        // without that frame and without its animation set.
        int frame = text.lastIndexOf("\nFrame ", mesh.start()) + 1;
        String header = text.substring(0, frame) + text.substring(blockEnd(text, frame));
        Matcher anims = Pattern.compile("(?m)^AnimationSet\\b").matcher(header);
        if (anims.find()) {
            header = header.substring(0, anims.start()) + header.substring(blockEnd(header, anims.start()));
        }
        Skeleton skeleton = new Skeleton(header, Mesh.readX(maleBody));
        // Frames nest: walk the braces to see which frame each sits in.
        Matcher token = Pattern.compile("Frame\\s+(\\S+)\\s*\\{|\\{|\\}").matcher(skeleton.header);
        List<String> open = new ArrayList<>();
        List<Integer> depths = new ArrayList<>();
        int depth = 0;
        while (token.find()) {
            if (token.group(1) != null) {
                depth++;
                skeleton.parents.put(token.group(1), open.isEmpty() ? null : open.get(open.size() - 1));
                open.add(token.group(1));
                depths.add(depth);
            } else if (token.group().equals("{")) {
                depth++;
            } else {
                if (!depths.isEmpty() && depths.get(depths.size() - 1) == depth) {
                    open.remove(open.size() - 1);
                    depths.remove(depths.size() - 1);
                }
                depth--;
            }
        }
        Matcher sw = Pattern.compile("SkinWeights\\s*\\{\\s*\"([^\"]+)\";\\s*(\\d+);").matcher(text);
        Pattern number = Pattern.compile("-?\\d+(?:\\.\\d+)?(?:e-?\\d+)?");
        while (sw.find()) {
            String bone = sw.group(1);
            int count = Integer.parseInt(sw.group(2));
            Matcher n = number.matcher(text);
            n.region(sw.end(), text.length());
            List<Double> values = new ArrayList<>();
            while (values.size() < count * 2 + 16 && n.find()) {
                values.add(Double.parseDouble(n.group()));
            }
            for (int i = 0; i < count; i++) {
                int vertex = (int) Math.round(values.get(i));
                if (vertex < skeleton.bodyWeights.size()) {
                    skeleton.bodyWeights.get(vertex).merge(bone, values.get(count + i), Double::sum);
                }
            }
            double[] offset = new double[16];
            for (int i = 0; i < 16; i++) {
                offset[i] = values.get(count * 2 + i);
            }
            skeleton.offsets.put(bone, offset);
            double[] world = invert(offset);
            skeleton.bindPositions.put(bone, new double[]{world[12], world[13], world[14]});
        }
        return skeleton;
    }

    /** The bone moving a triangle most, summed over its corners. */
    String mainBone(int[] face) {
        Map<String, Double> sum = new java.util.HashMap<>();
        for (int corner : face) {
            bodyWeights.get(corner).forEach((bone, w) -> sum.merge(bone, w, Double::sum));
        }
        String best = null;
        for (Map.Entry<String, Double> e : sum.entrySet()) {
            if (best == null || e.getValue() > sum.get(best)) {
                best = e.getKey();
            }
        }
        return best == null ? "" : best;
    }

    /** The index just past the block whose first brace follows `start`. */
    private static int blockEnd(String text, int start) {
        int depth = 0;
        for (int i = text.indexOf('{', start); i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i + 1;
            }
        }
        return text.length();
    }

    double[] bind(String bone) {
        return bindPositions.get(bone);
    }

    /** The inverse of a 4x4 matrix (Gauss-Jordan with partial pivoting). */
    static double[] invert(double[] m) {
        double[][] a = new double[4][8];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                a[r][c] = m[r * 4 + c];
            }
            a[r][4 + r] = 1;
        }
        for (int c = 0; c < 4; c++) {
            int p = c;
            for (int r = c + 1; r < 4; r++) {
                if (Math.abs(a[r][c]) > Math.abs(a[p][c])) {
                    p = r;
                }
            }
            double[] t = a[c];
            a[c] = a[p];
            a[p] = t;
            double d = a[c][c];
            for (int k = 0; k < 8; k++) {
                a[c][k] /= d;
            }
            for (int r = 0; r < 4; r++) {
                if (r != c) {
                    double f = a[r][c];
                    for (int k = 0; k < 8; k++) {
                        a[r][k] -= f * a[c][k];
                    }
                }
            }
        }
        double[] out = new double[16];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                out[r * 4 + c] = a[r][4 + c];
            }
        }
        return out;
    }
}
