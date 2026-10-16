package gielinor.builder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A triangle mesh with one colour a face, and reading and writing the text
 * DirectX `.X` files Project Zomboid loads its models from.
 */
final class Mesh {
    final List<double[]> vertices = new ArrayList<>();
    /** Vertex indices, three a face. */
    final List<int[]> faces = new ArrayList<>();
    /** RGB a face. */
    final List<Integer> colours = new ArrayList<>();
    /**
     * Each face's colour as the game stores it (packed hue, saturation and
     * lightness) before any recolouring: how the player's chosen colours
     * find the faces they colour. Empty for meshes not read from the game.
     */
    final List<Integer> sourceColours = new ArrayList<>();
    /**
     * The game's animation group of each vertex (-1 for none): the game
     * moves a group's vertices together, so a group is one part of a body,
     * such as an upper arm or a shin. Empty for meshes not read from the game.
     */
    final List<Integer> groups = new ArrayList<>();

    /** Reads the vertices of the first mesh in a text `.X` file (all a template needs). */
    static Mesh readX(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.ISO_8859_1);
        Mesh mesh = new Mesh();
        Matcher header = Pattern.compile("\\n\\s*Mesh\\s+[^\\s{]*\\s*\\{\\s*(\\d+);").matcher(text);
        if (!header.find()) {
            return mesh;
        }
        int count = Integer.parseInt(header.group(1));
        Matcher v = Pattern.compile("(-?[\\d.]+(?:e-?\\d+)?);\\s*(-?[\\d.]+(?:e-?\\d+)?);\\s*(-?[\\d.]+(?:e-?\\d+)?);[,;]").matcher(text);
        v.region(header.end(), text.length());
        while (mesh.vertices.size() < count && v.find()) {
            mesh.vertices.add(new double[]{Double.parseDouble(v.group(1)), Double.parseDouble(v.group(2)), Double.parseDouble(v.group(3))});
        }
        // Then the faces: a count, then "3;a,b,c;" each (larger faces are fanned into triangles).
        Matcher faceCount = Pattern.compile("\\G[\\s,;]*(\\d+);").matcher(text);
        if (faceCount.find(v.end())) {
            int faces = Integer.parseInt(faceCount.group(1));
            Matcher face = Pattern.compile("(\\d+);([\\d,]+);").matcher(text);
            face.region(faceCount.end(), text.length());
            while (mesh.faces.size() < faces && face.find()) {
                String[] idx = face.group(2).split(",");
                for (int k = 1; k + 1 < idx.length; k++) {
                    mesh.faces.add(new int[]{Integer.parseInt(idx[0]), Integer.parseInt(idx[k]), Integer.parseInt(idx[k + 1])});
                }
            }
        }
        return mesh;
    }

    /**
     * How many more faces' normals point out from the middle than in: which
     * way round the mesh is wound, positive when normals face outward.
     */
    int outwardVote() {
        double[] c = ModelExporter.centroid(vertices);
        int vote = 0;
        for (int i = 0; i < faces.size(); i++) {
            int[] f = faces.get(i);
            double[] mid = new double[3];
            for (int k = 0; k < 3; k++) {
                for (int j = 0; j < 3; j++) {
                    mid[j] += vertices.get(f[k])[j] / 3;
                }
            }
            double d = ModelExporter.dot(normal(i), ModelExporter.sub(mid, c));
            vote += d > 0 ? 1 : d < 0 ? -1 : 0;
        }
        return vote;
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    /**
     * Writes the mesh as a text `.X` file, each face flat shaded and pointing
     * at its colour's cell in the palette texture `texture`.
     */
    void writeX(Path file, String name, String texture, Palette palette) throws IOException {
        writeX(file, name, texture, palette, null, null, null);
    }

    /**
     * Writes the mesh as a text `.X` file, each face pointing at its own cell
     * of `atlas`, for the held items Zomboid paints blood on.
     */
    void writeX(Path file, String name, String texture, FaceAtlas atlas) throws IOException {
        writeX(file, name, texture, null, atlas, null, null);
    }

    /**
     * Writes the mesh skinned to Zomboid's skeleton: the skeleton's frames
     * first, then the mesh with one `SkinWeights` block per bone used.
     * `bones` names the bone each vertex is bound to.
     */
    void writeSkinnedX(Path file, String name, String texture, Palette palette, Skeleton skeleton, Bound[] bounds) throws IOException {
        writeX(file, name, texture, palette, null, skeleton, bounds);
    }

    private void writeX(Path file, String name, String texture, Palette palette, FaceAtlas atlas, Skeleton skeleton, Bound[] bounds)
            throws IOException {
        // With a palette, faces sharing a corner and a colour share the
        // written vertex; with an atlas every face's corners are its own, as
        // each face has its own cell. Each face gets its own flat normal
        // through the normal list either way.
        java.util.Map<Long, Integer> written = new java.util.HashMap<>();
        List<int[]> corners = new ArrayList<>();
        List<Integer> sources = new ArrayList<>();
        List<double[]> uvs = new ArrayList<>();
        for (int i = 0; i < faces.size(); i++) {
            int[] face = faces.get(i);
            int[] c = new int[3];
            for (int k = 0; k < 3; k++) {
                int vertex = face[k];
                int colour = colours.get(i) & 0xffffff;
                double[] uv = atlas != null ? atlas.uv(i, k) : palette.uv(colour);
                long key = atlas != null ? (long) i << 2 | k : (long) vertex << 24 | colour;
                c[k] = written.computeIfAbsent(key, x -> {
                    sources.add(vertex);
                    uvs.add(uv);
                    return sources.size() - 1;
                });
            }
            corners.add(c);
        }
        int n = faces.size();
        int count = sources.size();
        StringBuilder out = new StringBuilder(skeleton == null ? "xof 0303txt 0032\n\n" : skeleton.header);
        out.append("Material Gielinor {\n 1.000000;1.000000;1.000000;1.000000;;\n 0.000000;\n 0.000000;0.000000;0.000000;;\n 0.000000;0.000000;0.000000;;\n");
        out.append(" TextureFilename {\n  \"").append(texture).append("\";\n }\n}\n\n");
        out.append("Frame ").append(name).append(" {\n FrameTransformMatrix {\n  1.000000,0.000000,0.000000,0.000000,0.000000,1.000000,0.000000,0.000000,0.000000,0.000000,1.000000,0.000000,0.000000,0.000000,0.000000,1.000000;;\n }\n\n");
        out.append(" Mesh ").append(name).append(" {\n  ").append(count).append(";\n");
        for (int i = 0; i < count; i++) {
            double[] p = vertices.get(sources.get(i));
            out.append("  ").append(f(p[0])).append(';').append(f(p[1])).append(';').append(f(p[2])).append(i == count - 1 ? ";;\n" : ";,\n");
        }
        out.append("  ").append(n).append(";\n");
        for (int i = 0; i < n; i++) {
            int[] c = corners.get(i);
            out.append("  3;").append(c[0]).append(',').append(c[1]).append(',').append(c[2]).append(i == n - 1 ? ";;\n" : ";,\n");
        }
        out.append("\n  MeshNormals {\n   ").append(n).append(";\n");
        for (int i = 0; i < n; i++) {
            double[] nm = normal(i);
            out.append("   ").append(f(nm[0])).append(';').append(f(nm[1])).append(';').append(f(nm[2])).append(i == n - 1 ? ";;\n" : ";,\n");
        }
        out.append("   ").append(n).append(";\n");
        for (int i = 0; i < n; i++) {
            out.append("   3;").append(i).append(',').append(i).append(',').append(i).append(i == n - 1 ? ";;\n" : ";,\n");
        }
        out.append("  }\n\n  MeshMaterialList {\n   1;\n   ").append(n).append(";\n");
        for (int i = 0; i < n; i++) {
            out.append("   0").append(i == n - 1 ? ";\n" : ",\n");
        }
        out.append("   { Gielinor }\n  }\n\n  MeshTextureCoords {\n   ").append(count).append(";\n");
        for (int i = 0; i < count; i++) {
            double[] uv = uvs.get(i);
            out.append("   ").append(f(uv[0])).append(';').append(f(uv[1])).append(i == count - 1 ? ";;\n" : ";,\n");
        }
        out.append("  }\n");
        if (skeleton != null) {
            appendSkin(out, skeleton, bounds, sources);
        }
        out.append(" }\n}\n");
        Files.writeString(file, out, StandardCharsets.ISO_8859_1);
    }

    /**
     * The skin: one block a bone, listing the written vertices it moves and
     * how strongly. `sources` gives each written vertex's own vertex.
     */
    private void appendSkin(StringBuilder out, Skeleton skeleton, Bound[] bounds, List<Integer> sources) {
        java.util.Map<String, List<Integer>> byBone = new java.util.LinkedHashMap<>();
        java.util.Map<String, List<Double>> weights = new java.util.LinkedHashMap<>();
        int most = 1;
        for (int i = 0; i < sources.size(); i++) {
            Bound b = bounds[sources.get(i)];
            most = Math.max(most, b.bones().length);
            for (int j = 0; j < b.bones().length; j++) {
                byBone.computeIfAbsent(b.bones()[j], x -> new ArrayList<>()).add(i);
                weights.computeIfAbsent(b.bones()[j], x -> new ArrayList<>()).add(b.weights()[j]);
            }
        }
        out.append("\n  XSkinMeshHeader {\n   ").append(most).append(";\n   ").append(most * 3).append(";\n   ").append(byBone.size()).append(";\n  }\n");
        for (java.util.Map.Entry<String, List<Integer>> e : byBone.entrySet()) {
            List<Integer> idx = e.getValue();
            List<Double> w = weights.get(e.getKey());
            out.append("\n  SkinWeights {\n   \"").append(e.getKey()).append("\";\n   ").append(idx.size()).append(";\n");
            for (int i = 0; i < idx.size(); i++) {
                out.append("   ").append(idx.get(i)).append(i + 1 < idx.size() ? ",\n" : ";\n");
            }
            for (int i = 0; i < idx.size(); i++) {
                out.append("   ").append(f(w.get(i))).append(i + 1 < idx.size() ? ",\n" : ";\n");
            }
            double[] m = skeleton.offsets.get(e.getKey());
            out.append("   ");
            for (int i = 0; i < 16; i++) {
                out.append(f(m[i])).append(i < 15 ? "," : ";;\n");
            }
            out.append("  }\n");
        }
    }

    /** A face's unit normal, by its winding. */
    double[] normal(int face) {
        int[] f = faces.get(face);
        double[] a = vertices.get(f[0]);
        double[] b = vertices.get(f[1]);
        double[] c = vertices.get(f[2]);
        double[] u = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        double[] v = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
        double[] n = {u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]};
        double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        return len == 0 ? new double[]{0, 1, 0} : new double[]{n[0] / len, n[1] / len, n[2] / len};
    }

    /** Reverses every face's winding, which turns its normal round. */
    void flipWinding() {
        for (int[] f : faces) {
            int t = f[1];
            f[1] = f[2];
            f[2] = t;
        }
    }
}
