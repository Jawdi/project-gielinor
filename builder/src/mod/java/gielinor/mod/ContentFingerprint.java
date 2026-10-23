package gielinor.mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Identifies the builder, world layout and game version used for a content pack. */
final class ContentFingerprint {
    private ContentFingerprint() { }
    static String calculate(Path builder, Path recipeManifest, Path gameJar) throws IOException {
        try {
            var result = MessageDigest.getInstance("SHA-256");
            result.update("gielinor-content-recipe-v1\n".getBytes(StandardCharsets.UTF_8));
            for (Path file : List.of(builder, recipeManifest, gameJar)) {
                var digest = MessageDigest.getInstance("SHA-256");
                try (var in = Files.newInputStream(file)) {
                    byte[] block = new byte[1024 * 1024];
                    for (int count; (count = in.read(block)) != -1;) digest.update(block, 0, count);
                }
                result.update(digest.digest());
            }
            return HexFormat.of().formatHex(result.digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IOException("Cannot verify the builder, world package or game version", failure);
        }
    }
}
