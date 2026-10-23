package gielinor.builder;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Cache reuse and retry checks do not need the OSRS cache or game artwork. */
public final class WorldRecipeCacheTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private Path pack, out, game;
    private JsonObject manifest, receipt;
    private final List<String> steps=new ArrayList<>();
    private final WorldRecipe.Progress progress=new WorldRecipe.Progress() {
        public void step(String text) { steps.add(text); }
        public void drawn(int count,int total) { fail("The fake cache must never draw"); }
    };
    private void fixture() throws Exception {
        pack=temporary.newFolder("package").toPath();out=temporary.newFolder("world").toPath();game=temporary.newFolder("game").toPath();
        Files.createDirectories(pack.resolve("layout/common/media"));
        Files.writeString(pack.resolve("layout/common/media/marker.txt"),"layout");
        Files.writeString(game.resolve("projectzomboid.jar"),"fake game, without artwork");
        WorldRecipe.writeRecipes(pack.resolve("recipes.bin"),List.of("carrier"),new BitSet());
        manifest=new JsonObject();manifest.addProperty("schema",1);manifest.addProperty("sprites",1);manifest.addProperty("contentDigest","a".repeat(64));
        var files=new JsonObject();
        for(String name:List.of("recipes.bin","layout/common/media/marker.txt"))files.addProperty(name,WorldRecipe.sha(pack.resolve(name)));
        manifest.add("files",files);Files.writeString(pack.resolve("package.json"),manifest.toString());
        Files.writeString(out.resolve("preserve.txt"),"previous build");
        receipt=new JsonObject();receipt.addProperty("packageSHA256",WorldRecipe.sha(pack.resolve("package.json")));
        receipt.addProperty("gameJarSHA256",WorldRecipe.sha(game.resolve("projectzomboid.jar")));
        receipt.addProperty("builderSHA256",WorldRecipe.builderIdentity());receipt.addProperty("contentDigest","a".repeat(64));
        var payload=new JsonObject();payload.addProperty("preserve.txt",WorldRecipe.sha(out.resolve("preserve.txt")));receipt.add("payloadSHA256",payload);
        Files.writeString(out.resolve("built-world.json"),receipt.toString());
    }
    private Path build() throws Exception { return WorldRecipe.build(pack,pack.toFile(),game,out,1,progress); }
    private void refusesFakeGameAndPreservesPreviousBuild() throws Exception {
        String before=Files.readString(out.resolve("built-world.json"));
        assertThrows(Exception.class,this::build);
        assertEquals(before,Files.readString(out.resolve("built-world.json")));
        assertFalse(steps.contains("The world is already built from this version"));
        try(var siblings=Files.list(out.getParent())) {
            assertFalse(siblings.anyMatch(p->p.getFileName().toString().startsWith("world.building-")));
        }
    }
    @Test public void reusesVerifiedBuildWithoutOpeningGameArtworkOrSourceCache() throws Exception {
        fixture();assertEquals(out.toAbsolutePath(),build());
        assertTrue(steps.contains("The world is already built from this version"));
    }
    @Test public void layoutUpdateStartsNewBuildAndFailedAttemptKeepsOldBuild() throws Exception {
        fixture();Files.writeString(pack.resolve("layout/common/media/marker.txt"),"updated layout");
        manifest.getAsJsonObject("files").addProperty("layout/common/media/marker.txt",WorldRecipe.sha(pack.resolve("layout/common/media/marker.txt")));
        Files.writeString(pack.resolve("package.json"),manifest.toString());refusesFakeGameAndPreservesPreviousBuild();
        assertEquals("previous build",Files.readString(out.resolve("preserve.txt")));
    }
    @Test public void gameUpdateInvalidatesTheCachedWorld() throws Exception {
        fixture();Files.writeString(game.resolve("projectzomboid.jar"),"other game version");refusesFakeGameAndPreservesPreviousBuild();
    }
    @Test public void builderUpdateInvalidatesTheCachedWorld() throws Exception {
        fixture();receipt.addProperty("builderSHA256","b".repeat(64));Files.writeString(out.resolve("built-world.json"),receipt.toString());
        refusesFakeGameAndPreservesPreviousBuild();
    }
    @Test public void damagedWorldCanRetryInsteadOfGettingStuckOnItsCacheReceipt() throws Exception {
        fixture();Files.writeString(out.resolve("preserve.txt"),"damaged");refusesFakeGameAndPreservesPreviousBuild();
        assertTrue(steps.contains("Rebuilding the damaged world cache"));
        assertEquals("damaged",Files.readString(out.resolve("preserve.txt")));
    }
}
