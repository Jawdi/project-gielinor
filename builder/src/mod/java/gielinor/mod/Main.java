package gielinor.mod;

import zombie.network.GameServer;

/**
 * Run by ZombieBuddy as the mod loads, before the game loads any mod's
 * files. A dedicated server has no main menu to build from, so there this
 * builds the content pack the first time (and after an update) and lets
 * the server find it, so it loads in the same start.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (!GameServer.server) {
            return;
        }
        if (!GameServer.ServerMods.contains(ContentBuild.PACK_ID)) {
            log("the server's Mods list needs " + ContentBuild.PACK_ID + ", before ProjectGielinor");
        }
        String state = ContentBuild.state();
        if (state.equals("ready")) {
            return;
        }
        log("the content pack is " + state + ", so it's being built now; this takes several minutes");
        boolean built = ContentBuild.buildAndWait(line -> {
            if (line.startsWith("# ")) {
                log(line.substring(2));
            }
        });
        if (built) {
            ContentBuild.forgetModFolders();
            log("content pack built");
        } else {
            log("the content pack wasn't built: " + ContentBuild.progress() + ". The builder's output is in " + ContentBuild.log());
        }
    }

    private static void log(String message) {
        System.out.println("Project Gielinor: " + message);
    }
}
