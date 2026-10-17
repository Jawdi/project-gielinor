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
    /** Each body vertex's texture coordinates: where on Zomboid's body texture (and its masks) it is. */
    final List<double[]> bodyUvs;
    /** How strongly each bone moves each body vertex, as the body's own skin has it. */
    final List<Map<String, Double>> bodyWeights = new ArrayList<>();
    /** Each frame's parent frame, from the frame hierarchy. */
    final Map<String, String> parents = new LinkedHashMap<>();

    private Skeleton(String header, Mesh body) {
        this.header = header;
        this.bodyVertices = body.vertices;
        this.bodyFaces = body.faces;
        this.bodyUvs = body.uvs;
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

    /** The point on a body triangle with these corner weights. */
    double[] bodyPoint(int[] f, double[] w) {
        double[] out = new double[3];
        for (int k = 0; k < 3; k++) {
            for (int c = 0; c < 3; c++) {
                out[c] += bodyVertices.get(f[k])[c] * w[k];
            }
        }
        return out;
    }

    /** The weights of a point projected onto a triangle's plane (not kept inside it). */
    double[] planeWeights(double[] p, int[] f) {
        double[] a = bodyVertices.get(f[0]);
        double[] b = bodyVertices.get(f[1]);
        double[] c = bodyVertices.get(f[2]);
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
    double[] closestWeights(double[] p, int[] f) {
        double[] w = planeWeights(p, f);
        if (w[0] >= 0 && w[1] >= 0 && w[2] >= 0) {
            return w;
        }
        // Outside: the nearest point on one of its edges.
        double best = Double.MAX_VALUE;
        double[] out = {1, 0, 0};
        for (int k = 0; k < 3; k++) {
            double[] a = bodyVertices.get(f[k]);
            double[] b = bodyVertices.get(f[(k + 1) % 3]);
            double[] ab = ModelExporter.sub(b, a);
            double len = ModelExporter.dot(ab, ab);
            double t = len == 0 ? 0 : Math.max(0, Math.min(1, ModelExporter.dot(ModelExporter.sub(p, a), ab) / len));
            double[] q = ModelExporter.add(a, ModelExporter.scale(ab, t));
            double[] pq = ModelExporter.sub(p, q);
            double d = Math.sqrt(ModelExporter.dot(pq, pq));
            if (d < best) {
                best = d;
                out = new double[3];
                out[k] = 1 - t;
                out[(k + 1) % 3] = t;
            }
        }
        return out;
    }

    /** A point on the body: its triangle, and the point's weights on the triangle's corners. */
    record Spot(int[] face, double[] weights) {
    }

    /** Each body vertex's island: the piece of the body's texture it's on, found when first wanted. */
    private int[] islands;

    /**
     * The island of the body's texture a body triangle is on. The body's
     * vertices are split where its texture is (each has one place on it), so
     * triangles sharing a vertex are on one island.
     */
    int island(int[] face) {
        if (islands == null) {
            int[] parent = new int[bodyVertices.size()];
            for (int i = 0; i < parent.length; i++) {
                parent[i] = i;
            }
            for (int[] f : bodyFaces) {
                union(parent, f[0], f[1]);
                union(parent, f[1], f[2]);
            }
            islands = new int[parent.length];
            for (int i = 0; i < parent.length; i++) {
                islands[i] = root(parent, i);
            }
        }
        return islands[face[0]];
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[root(parent, a)] = root(parent, b);
    }

    /** The point nearest `p` on one island of the body's texture (see {@link #island}). */
    Spot nearestSpot(double[] p, int island) {
        int[] nearest = null;
        double[] at = {1, 0, 0};
        double best = Double.MAX_VALUE;
        for (int[] f : bodyFaces) {
            if (island(f) != island) {
                continue;
            }
            double[] w = closestWeights(p, f);
            double[] d = ModelExporter.sub(p, bodyPoint(f, w));
            double distance = ModelExporter.dot(d, d);
            if (distance < best) {
                best = distance;
                nearest = f;
                at = w;
            }
        }
        return new Spot(nearest, at);
    }

    /** The point nearest `p` on the skin of a part of the body. */
    Spot nearestSpot(double[] p, BodyPart part) {
        int[] nearest = null;
        double[] at = {1, 0, 0};
        double best = Double.MAX_VALUE;
        for (int[] f : bodyFaces) {
            if (!part.skins(mainBone(f))) {
                continue;
            }
            double[] w = closestWeights(p, f);
            double[] d = ModelExporter.sub(p, bodyPoint(f, w));
            double distance = ModelExporter.dot(d, d);
            if (distance < best) {
                best = distance;
                nearest = f;
                at = w;
            }
        }
        return new Spot(nearest, at);
    }

    /**
     * The point on a part's skin straight out through `p` from the line down
     * the part's middle (or the nearest, if there's none out that way). The
     * game's limbs lie inside Zomboid's; nearest a point inside an arm may be
     * the arm's far side, where straight out it's always the near side, as
     * far round as the point is round its own.
     */
    Spot spotOutFrom(double[] p, BodyPart part) {
        double[] axis = nearestOnAxis(p, part);
        double[] out = ModelExporter.sub(p, axis);
        if (ModelExporter.dot(out, out) < 1e-8) {
            return nearestSpot(p, part);
        }
        double best = Double.MAX_VALUE;
        Spot spot = null;
        for (int[] f : bodyFaces) {
            if (!part.skins(mainBone(f))) {
                continue;
            }
            double[] hit = rayHit(axis, out, f);
            if (hit != null && hit[3] < best) {
                best = hit[3];
                spot = new Spot(f, new double[]{hit[0], hit[1], hit[2]});
            }
        }
        return spot != null ? spot : nearestSpot(p, part);
    }

    /** The point nearest `p` on the line down a part's middle, joint to joint. */
    private double[] nearestOnAxis(double[] p, BodyPart part) {
        // Joints the body's skin doesn't hang from (the toes) have no place.
        List<double[]> joints = new ArrayList<>();
        for (String joint : part.axis()) {
            if (bindPositions.containsKey(joint)) {
                joints.add(bindPositions.get(joint));
            }
        }
        double best = Double.MAX_VALUE;
        double[] out = joints.get(0);
        for (int i = 0; i + 1 < joints.size(); i++) {
            double[] a = joints.get(i);
            double[] b = joints.get(i + 1);
            double[] ab = ModelExporter.sub(b, a);
            double len = ModelExporter.dot(ab, ab);
            double t = len == 0 ? 0 : Math.max(0, Math.min(1, ModelExporter.dot(ModelExporter.sub(p, a), ab) / len));
            double[] q = ModelExporter.add(a, ModelExporter.scale(ab, t));
            double[] d = ModelExporter.sub(p, q);
            if (ModelExporter.dot(d, d) < best) {
                best = ModelExporter.dot(d, d);
                out = q;
            }
        }
        return out;
    }

    /** Where the ray from `from` along `dir` (forwards only) meets a body triangle: its weights and the distance along, or null. */
    private double[] rayHit(double[] from, double[] dir, int[] f) {
        double[] a = bodyVertices.get(f[0]);
        double[] e1 = ModelExporter.sub(bodyVertices.get(f[1]), a);
        double[] e2 = ModelExporter.sub(bodyVertices.get(f[2]), a);
        double[] h = ModelExporter.cross(dir, e2);
        double det = ModelExporter.dot(e1, h);
        if (Math.abs(det) < 1e-12) {
            return null;
        }
        double[] s = ModelExporter.sub(from, a);
        double u = ModelExporter.dot(s, h) / det;
        if (u < 0 || u > 1) {
            return null;
        }
        double[] q = ModelExporter.cross(s, e1);
        double v = ModelExporter.dot(dir, q) / det;
        if (v < 0 || u + v > 1) {
            return null;
        }
        double t = ModelExporter.dot(e2, q) / det;
        return t > 0 ? new double[]{1 - u - v, u, v, t} : null;
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
