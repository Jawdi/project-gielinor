package gielinor.runtime;

import java.lang.reflect.*;
import java.util.*;
import org.lwjgl.opengl.GL11;
import zombie.core.opengl.RenderThread;
import zombie.core.textures.*;
import zombie.tileDepth.TileDepthTexture;

/** Render-context LRU for imported R8 depth images, preserving Texture objects. */
public final class DepthGpuCache {
    static final int IMAGE_BYTES=128*256;
    static final long LIMIT=Long.getLong("gielinor.depth.gpuBytes",512L*1024*1024);
    static final LinkedHashMap<TextureID,Entry> entries=new LinkedHashMap<>(128,0.75f,true);
    static final Field id;
    static final Method generate,upload;
    public static long residentBytes,peakResidentBytes,evictions,restorations;
    static final class Entry {
        final TileDepthTexture depth;final TextureID texture;
        boolean uploaded,resident,restoring;
        Entry(TileDepthTexture depth,TextureID texture){this.depth=depth;this.texture=texture;}
    }
    static {
        try {
            if(LIMIT<IMAGE_BYTES)throw new IllegalArgumentException("Depth GPU limit smaller than one image");
            id=TextureID.class.getDeclaredField("id");id.setAccessible(true);
            generate=TextureID.class.getDeclaredMethod("generateHwId",boolean.class);generate.setAccessible(true);
            upload=TileDepthTexture.class.getDeclaredMethod("lambda$updateGPUTexture$0");upload.setAccessible(true);
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    public static void register(Object object,Object nativeTexture) {
        if(!DepthStreamRuntime.manages(object)||nativeTexture==null)return;
        synchronized(entries){var key=((Texture)nativeTexture).getTextureId();entries.computeIfAbsent(key,k->new Entry((TileDepthTexture)object,k));}
    }
    public static void uploaded(Object object) {
        if(!DepthStreamRuntime.manages(object))return;
        var depth=(TileDepthTexture)object;var texture=depth.getTexture();register(depth,texture);
        synchronized(entries) {
            var entry=entries.get(texture.getTextureId());entry.uploaded=true;
            if(!entry.resident){entry.resident=true;residentBytes+=IMAGE_BYTES;}
            peakResidentBytes=Math.max(peakResidentBytes,residentBytes);
            try {
                var iterator=entries.entrySet().iterator();
                while(residentBytes>LIMIT&&iterator.hasNext()) {
                    Entry old=iterator.next().getValue();if(old==entry||!old.resident||old.restoring)continue;
                    int handle=id.getInt(old.texture);
                    if(handle>=0)GL11.glDeleteTextures(handle);
                    id.setInt(old.texture,-1);old.resident=false;residentBytes-=IMAGE_BYTES;evictions++;
                    Texture.lastTextureID=-1;
                }
            }catch(Exception e){throw new IllegalStateException("Depth GPU eviction failed",e);}
        }
    }
    public static void ensure(Object object) {
        Entry entry;
        synchronized(entries) {
            entry=entries.get(object);
            if(entry==null||!entry.uploaded||entry.restoring||entry.resident)return;
        }
        // getID may be queried by the game thread; GL work always stays on the
        // render context. Pending first uploads use B42's original queue order.
        final Entry target=entry;
        RenderThread.invokeOnRenderContext(()->restore(target));
    }
    public static void ensureTexture(Object object){ensure(((Texture)object).getTextureId());}
    static void restore(Entry entry) {
        synchronized(entries){if(entry.resident||entry.restoring)return;entry.restoring=true;}
        try {
            DepthStreamRuntime.materialize(entry.depth);
            generate.invoke(entry.texture,false);
            upload.invoke(entry.depth);
            restorations++;
        }catch(Exception e){throw new IllegalStateException("Depth GPU restoration failed",e);}
        finally{synchronized(entries){entry.restoring=false;}}
    }
}
