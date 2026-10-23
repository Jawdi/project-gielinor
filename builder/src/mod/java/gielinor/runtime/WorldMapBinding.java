package gielinor.runtime;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.iso.IsoChunk;
import zombie.iso.worldgen.WorldGenParams;
import zombie.network.*;

/** Bind this selected standalone map to its real files, with no procedural fill. */
public final class WorldMapBinding {
    static final String MAP=System.getProperty("gielinor.depth.map", "OriginalGeometryPrototype");
    static volatile boolean active;
    static Set<Long> cells=Set.of();
    static Boolean priorWorldgen;
    static long key(int x,int y){return ((long)x<<32)|(y&0xffffffffL);}
    public static synchronized ArrayList<String> selectedFolders() {
        String selected=GameClient.client?GameClient.gameMap:GameServer.server?GameServer.gameMap:Core.gameMap;
        if(!MAP.equals(selected)||!DepthStreamRuntime.worldActive()) {
            if(active&&priorWorldgen!=null)IsoChunk.doWorldgen=priorWorldgen;
            active=false;return null;
        }
        Core.gameMap=selected;
        try {
            Path mod=Path.of(Objects.requireNonNull(System.getProperty("gielinor.depth.mod")));
            Path expected=mod.resolve("common/media/maps/"+MAP).toRealPath();
            String virtual="media/maps/"+MAP+"/";
            Path mapped=Path.of(ZomboidFileSystem.instance.getDirectoryString(virtual));
            if(!Files.isDirectory(mapped)||!mapped.toRealPath().equals(expected)) {
                ZomboidFileSystem.instance.loadMod(System.getProperty("gielinor.depth.modId", "GielinorWorldPrototype"));
                mapped=Path.of(ZomboidFileSystem.instance.getDirectoryString(virtual));
            }
            if(!Files.isDirectory(mapped)||!mapped.toRealPath().equals(expected))throw new IllegalStateException("Native map directory is not bound to the installed OSRS files: "+mapped);
            var found=new HashSet<Long>();int minX=Integer.MAX_VALUE,minY=minX,maxX=Integer.MIN_VALUE,maxY=maxX;
            try(var files=Files.list(expected)) {
                for(Path file:files.toList()) {
                    var match=Pattern.compile("(-?\\d+)_(-?\\d+)\\.lotheader").matcher(file.getFileName().toString());if(!match.matches())continue;
                    int x=Integer.parseInt(match.group(1)),y=Integer.parseInt(match.group(2));found.add(key(x,y));
                    minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);
                }
            }
            if(found.isEmpty())throw new IllegalStateException("Selected OSRS map contains no native cell headers");
            cells=Set.copyOf(found);
            if(!active)priorWorldgen=IsoChunk.doWorldgen;
            IsoChunk.doWorldgen=false;active=true;
            var params=WorldGenParams.INSTANCE;
            params.setMinXCell(minX);params.setMinYCell(minY);params.setMaxXCell(maxX);params.setMaxYCell(maxY);
            System.out.println("GIELINOR map binding: "+selected+" -> "+mapped+"; "+cells.size()+" cells; procedural background disabled");
            return new ArrayList<>(List.of(MAP));
        }catch(Exception e){throw new IllegalStateException("Selected OSRS map cannot be loaded; refusing generated-world fallback",e);}
    }
    public static boolean outsideSquare(int x,int y){return active&&!cells.contains(key(Math.floorDiv(x,256),Math.floorDiv(y,256)));}
    public static boolean outsideChunk(int x,int y){return active&&!cells.contains(key(Math.floorDiv(x,32),Math.floorDiv(y,32)));}
}
