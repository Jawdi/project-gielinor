package gielinor.builder;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class WorldArtWriterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void spillsToDiskAndRemovesScratchOnClose() throws Exception {
        Path scratch = temporary.getRoot().toPath().resolve("frames.tmp");
        var image = new BufferedImage(128, 256, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(64, 200, 0xff125678);
        try (var writer = new WorldArtWriter(1, new BitSet(), scratch)) {
            writer.accept(0, new ArtRecipes.Drawn(image, null));
            assertTrue(writer.heldBytes() > 0);
            assertEquals(writer.heldBytes(), Files.size(scratch));
            assertEquals(64, writer.contentDigest().length());
            assertThrows(Exception.class, () -> writer.accept(0, new ArtRecipes.Drawn(image, null)));
        }
        assertFalse(Files.exists(scratch));
    }
    @Test public void refusesToTruncateAnExistingScratchFile() throws Exception {
        Path scratch = temporary.newFile().toPath();
        Files.writeString(scratch, "keep");
        assertThrows(Exception.class, () -> new WorldArtWriter(1, new BitSet(), scratch));
        assertEquals("keep", Files.readString(scratch));
    }
}
