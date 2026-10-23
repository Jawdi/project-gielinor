package gielinor.mod;

import java.nio.file.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class ContentFingerprintTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void invalidatesOnBuilderLayoutOrGameUpdates() throws Exception {
        Path builder = temporary.newFile("builder.jar").toPath(), manifest = temporary.newFile("package.json").toPath(),
            game = temporary.newFile("game.jar").toPath();
        Files.writeString(builder, "builder"); Files.writeString(manifest, "layout"); Files.writeString(game, "game");
        String initial = ContentFingerprint.calculate(builder, manifest, game);
        assertEquals(initial, ContentFingerprint.calculate(builder, manifest, game));
        for (Path input : java.util.List.of(builder, manifest, game)) {
            String before = Files.readString(input);
            Files.writeString(input, before + " update");
            assertNotEquals(initial, ContentFingerprint.calculate(builder, manifest, game));
            Files.writeString(input, before);
            assertEquals(initial, ContentFingerprint.calculate(builder, manifest, game));
        }
    }
    @Test public void refusesMissingInputsRatherThanAnUnknownReadyStamp() throws Exception {
        Path file = temporary.newFile().toPath(), missing = temporary.getRoot().toPath().resolve("missing");
        assertThrows(java.io.IOException.class, () -> ContentFingerprint.calculate(file, missing, file));
    }
}
