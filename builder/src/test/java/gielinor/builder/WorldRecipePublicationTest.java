package gielinor.builder;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class WorldRecipePublicationTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private Path pack,builder;
    private JsonObject manifest,proof;
    private void fixture() throws Exception {
        pack=temporary.newFolder().toPath();builder=temporary.newFile("builder.jar").toPath();Files.writeString(builder,"builder fixture");
        Files.createDirectories(pack.resolve("layout"));Files.writeString(pack.resolve("layout/marker.txt"),"layout");
        WorldRecipe.writeRecipes(pack.resolve("recipes.bin"),List.of("carrier"),new BitSet());
        manifest=new JsonObject();manifest.addProperty("schema",1);manifest.addProperty("sprites",1);manifest.addProperty("contentDigest","a".repeat(64));
        var files=new JsonObject();for(String name:List.of("recipes.bin","layout/marker.txt"))files.addProperty(name,WorldRecipe.sha(pack.resolve(name)));
        manifest.add("files",files);Files.writeString(pack.resolve("package.json"),manifest.toString());
        proof=new JsonObject();proof.addProperty("schema",1);proof.addProperty("verified",true);proof.addProperty("completeCells",1);
        proof.addProperty("sprites",1);proof.addProperty("contentDigest","a".repeat(64));
        proof.addProperty("packageSHA256",WorldRecipe.sha(pack.resolve("package.json")));proof.addProperty("builderSHA256",WorldRecipe.sha(builder));save();
    }
    private void save() throws Exception { Files.writeString(pack.resolve("publish-proof.json"),proof.toString()); }
    @Test public void acceptsMatchingPackageAndBuilderProof() throws Exception { fixture();WorldRecipeTool.verifyForPublication(pack,builder); }
    @Test public void refusesChangedBuilder() throws Exception {
        fixture();Files.writeString(builder,"updated builder");assertThrows(Exception.class,()->WorldRecipeTool.verifyForPublication(pack,builder));
    }
    @Test public void refusesLayoutUpdateWithoutAnotherProof() throws Exception {
        fixture();Files.writeString(pack.resolve("layout/marker.txt"),"updated layout");
        manifest.getAsJsonObject("files").addProperty("layout/marker.txt",WorldRecipe.sha(pack.resolve("layout/marker.txt")));
        Files.writeString(pack.resolve("package.json"),manifest.toString());assertThrows(Exception.class,()->WorldRecipeTool.verifyForPublication(pack,builder));
    }
    @Test public void refusesUnverifiedOrEmptyCoverage() throws Exception {
        fixture();proof.addProperty("verified",false);save();assertThrows(Exception.class,()->WorldRecipeTool.verifyForPublication(pack,builder));
        proof.addProperty("verified",true);proof.addProperty("completeCells",0);save();assertThrows(Exception.class,()->WorldRecipeTool.verifyForPublication(pack,builder));
    }
    @Test public void refusesMissingProof() throws Exception {
        fixture();Files.delete(pack.resolve("publish-proof.json"));assertThrows(Exception.class,()->WorldRecipeTool.verifyForPublication(pack,builder));
    }
}
