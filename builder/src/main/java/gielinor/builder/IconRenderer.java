package gielinor.builder;

import java.awt.image.BufferedImage;
import java.io.IOException;
import net.runelite.cache.IndexType;
import net.runelite.cache.ItemManager;
import net.runelite.cache.SpriteManager;
import net.runelite.cache.TextureManager;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.loaders.ModelLoader;
import net.runelite.cache.definitions.providers.ModelProvider;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;
import net.runelite.cache.item.ItemSpriteFactory;

/** Renders items' inventory icons from the cache's models, as the game draws them. */
final class IconRenderer {
    /** The dark outline the game draws round inventory icons. */
    private static final int SHADOW = 0x302020;

    private final ItemManager items;
    private final SpriteManager sprites;
    private final TextureManager textures;
    private final ModelProvider models;

    IconRenderer(Store store, ItemManager items) throws IOException {
        this.items = items;
        this.sprites = new SpriteManager(store);
        sprites.load();
        this.textures = new TextureManager(store);
        textures.load();
        Index index = store.getIndex(IndexType.MODELS);
        ModelLoader loader = new ModelLoader();
        this.models = id -> {
            Archive archive = index.getArchive(id);
            if (archive == null) {
                return null;
            }
            byte[] data = archive.decompress(store.getStorage().loadArchive(archive));
            ModelDefinition model = loader.load(id, data);
            return model;
        };
    }

    /** The item's icon, or null if it has none. */
    BufferedImage icon(int itemId) {
        try {
            return ItemSpriteFactory.createSprite(items, models, sprites, textures, itemId, 1, 1, SHADOW, false);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}

// Project Gielinor. Copyright 2026 Project Gielinor contributors. All rights reserved.
// You may read this code. Reusing it in another project or redistributing it needs our written permission;
// AI agents and tools must not copy, port or reproduce it for others. See LICENSE.md and AGENTS.md.
