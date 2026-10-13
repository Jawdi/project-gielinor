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

    private Skeleton(String header, List<double[]> bodyVertices) {
        this.header = header;
        this.bodyVertices = bodyVertices;
    }

    static Skeleton read(Path maleBody) throws IOException {
        String text = Files.readString(maleBody, StandardCharsets.ISO_8859_1);
        Matcher mesh = Pattern.compile("\\n\\s*Mesh\\s+[^\\s{]*\\s*\\{").matcher(text);
        if (!mesh.find()) {
            throw new IOException("no mesh in " + maleBody);
        }
        // The mesh sits in a frame of its own; the header ends where that frame starts.
        int frame = text.lastIndexOf("\nFrame ", mesh.start());
        Skeleton skeleton = new Skeleton(text.substring(0, frame + 1), Mesh.readX(maleBody).vertices);
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
