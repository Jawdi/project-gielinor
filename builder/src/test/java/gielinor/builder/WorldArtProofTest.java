package gielinor.builder;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.BitSet;
import javax.imageio.ImageIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class WorldArtProofTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void readsPackedOffsetsAlphaAndDepthAliases() throws Exception {
        Path media = temporary.newFolder().toPath();
        var image = new BufferedImage(128, 256, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(65, 193, 0x80123456);
        var depth = new BufferedImage(128, 256, BufferedImage.TYPE_INT_ARGB);
        depth.setRGB(65, 193, 0xffabcdef);
        var bits = new BitSet(); bits.set(0, 2);
        String expected;
        try (var writer = new WorldArtWriter(2, bits, media.resolve("frames.tmp"))) {
            writer.accept(1, new ArtRecipes.Drawn(image, depth));
            writer.accept(0, new ArtRecipes.Drawn(image, depth));
            expected = writer.contentDigest();
            writer.write(media, 2, null);
        }
        var proof = WorldArtProof.inspect(media, 2);
        assertEquals(expected, proof.contentDigest());
        assertEquals(2, proof.depthFrames());
        assertTrue(Files.readString(media.resolve("tileDepthTextureAssignments.txt")).contains("original_geometry_01_1 = original_geometry_01_0"));
        Path atlas = media.resolve("depthmaps/DEPTH_original_geometry_01.png");
        var changed = ImageIO.read(atlas.toFile()); changed.setRGB(65, 193, 0xffabcdee);
        ImageIO.write(changed, "png", atlas.toFile());
        assertNotEquals(expected, WorldArtProof.inspect(media, 2).contentDigest());
    }
    @Test public void representativeOrderCrossesSheet99AsNativeFileNamesDo() {
        var order = WorldArtWriter.sheetOrder(101 * 512);
        assertTrue(order.indexOf(99) < order.indexOf(98));
        assertTrue(order.indexOf(100) < order.indexOf(98));
    }
    @Test public void resolvesVariantsAndRefusesCycles() {
        assertArrayEquals(new String[]{"carrier", "carrier", "carrier"},
            WorldArtBuilder.resolve(java.util.List.of("carrier", "variant|original_geometry_01_0", "variant|original_geometry_01_1")));
        assertThrows(Exception.class, () -> WorldArtBuilder.resolve(java.util.List.of("variant|original_geometry_01_1", "variant|original_geometry_01_0")));
    }
}
