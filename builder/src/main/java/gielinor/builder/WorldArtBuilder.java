package gielinor.builder;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.fs.Store;

/**
 * Draws every sprite of a world from its recipe on all cores: the player
 * side of the world recipe (WORLD-RECIPE-PLAN.md). Recipes naming pieces of
 * the same object share one render, identical recipes are drawn once,
 * variants take the drawing of the sprite they copy, and each drawing thread
 * shares the cache's read-only metadata and synchronized disk readers.
 *
 * Drawings are handed to a sink as they finish, from the drawing threads, so
 * a whole world never sits in memory. One drawing can reach the sink under
 * several sprite indices; sinks must not change it.
 */
final class WorldArtBuilder {
    private WorldArtBuilder() {
    }

    interface Sink {
        void accept(int sprite, ArtRecipes.Drawn drawn) throws Exception;
    }

    interface Progress {
        void drawn(int sprites, int total);
    }

    /** A world sprite's index from its name (original_geometry_SHEET_INDEX, sheets counted from 1); -1 for other names. */
    static int index(String name) {
        if (!name.startsWith("original_geometry_")) return -1;
        var p = name.substring("original_geometry_".length()).split("_");
        return (Integer.parseInt(p[0]) - 1) * 512 + Integer.parseInt(p[1]);
    }

    /** Each sprite's recipe with variants replaced by the recipe of the sprite they copy. */
    static String[] resolve(List<String> recipes) {
        var resolved = new String[recipes.size()];
        for (int i = 0; i < resolved.length; i++) {
            String recipe = recipes.get(i);
            for (int hops = 0; recipe != null && recipe.startsWith("variant|"); hops++) {
                if (hops > resolved.length) throw new IllegalStateException("Variant cycle at sprite " + i);
                int base = index(recipe.substring("variant|".length()));
                recipe = base < 0 || base >= resolved.length ? null : recipes.get(base);
            }
            if (recipe == null) throw new IllegalStateException("Sprite " + i + " has no drawable recipe: " + recipes.get(i));
            resolved[i] = recipe;
        }
        return resolved;
    }

    /** Draws all sprites with the given number of threads, handing each to the sink. */
    static void draw(List<String> recipes, File cache, int threads, Sink sink, Progress progress) throws Exception {
        String[] resolved = resolve(recipes);
        var groups = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < resolved.length; i++) {
            String object = ArtRecipes.object(resolved[i]);
            groups.computeIfAbsent(object == null ? resolved[i] : object, k -> new ArrayList<>()).add(i);
        }
        // Store.load, ObjectManager.load and SpriteManager.load decode large tables.
        // Loading them for each drawing thread exhausted the player's 3 GB heap.
        // RuneLite's DataFile/IndexFile reads and texture loads synchronize access;
        // ModelLoader has no mutable fields. Drawings and moved meshes stay local.
        try (var store = new Store(cache)) {
                store.load();
                var objects = new ObjectManager(store);
                objects.load();
                var context = new ArtRecipes.Context(new ModelExporter(store), objects, new GielinorTerrain(store), new ConcurrentHashMap<>(), name -> {
                    throw new IllegalStateException("Variants are resolved before drawing");
                });
        WorldArt.ART.clear();
        var done = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(threads);
        try {
            var tasks = new ArrayList<Future<?>>(groups.size());
            for (var group : groups.entrySet()) {
                tasks.add(pool.submit(() -> {
                    var c = context;
                    String key = group.getKey();
                    var sprites = group.getValue();
                    if (ArtRecipes.object(resolved[sprites.get(0)]) != null) {
                        var pieces = ArtRecipes.pieces(key, c);
                        for (int sprite : sprites) sink.accept(sprite, ArtRecipes.pick(pieces, resolved[sprite]));
                    } else {
                        var drawn = ArtRecipes.draw(key, c);
                        if (drawn == null) throw new IllegalStateException("This builder can't draw recipe " + key);
                        for (int sprite : sprites) sink.accept(sprite, drawn);
                    }
                    if (progress != null) progress.drawn(done.addAndGet(sprites.size()), resolved.length);
                    return null;
                }));
            }
            for (var task : tasks) {
                try {
                    task.get();
                } catch (ExecutionException e) {
                    throw e.getCause() instanceof Exception x ? x : e;
                }
            }
        } finally {
            pool.shutdownNow();
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("World drawing workers did not stop");
            WorldArt.ART.clear();
        }
        }
    }
}
