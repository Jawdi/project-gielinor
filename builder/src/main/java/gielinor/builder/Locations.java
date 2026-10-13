package gielinor.builder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Where the game cache, Project Zomboid and Zomboid's mods folder usually live. */
final class Locations {
    private Locations() {
    }

    private static File home() {
        return new File(System.getProperty("user.home"));
    }

    /** A folder holding an OSRS cache: `main_file_cache.dat2` and its index files. */
    static boolean isCache(File dir) {
        return new File(dir, "main_file_cache.dat2").isFile() && new File(dir, "main_file_cache.idx255").isFile();
    }

    /**
     * The cache of an installed Old School RuneScape: RuneLite's, the official
     * client's (under ProgramData or the user's home) or the older Java
     * client's, whichever is found first.
     */
    static Optional<File> findCache() {
        List<File> candidates = new ArrayList<>();
        candidates.add(new File(home(), ".runelite/jagexcache/oldschool/LIVE"));
        String programData = System.getenv("ProgramData");
        if (programData != null) {
            candidates.add(new File(programData, "Jagex/Old School RuneScape"));
            candidates.add(new File(programData, "Jagex/oldschool"));
        }
        candidates.add(new File(home(), "jagexcache/oldschool/LIVE"));
        return candidates.stream().filter(Locations::isCache).findFirst();
    }

    /** Project Zomboid's install folder, from Steam's usual library folders on any drive. */
    static Optional<File> findZomboid() {
        List<File> candidates = new ArrayList<>();
        for (char drive = 'A'; drive <= 'Z'; drive++) {
            String root = drive + ":/";
            candidates.add(new File(root + "SteamLibrary/steamapps/common/ProjectZomboid"));
            candidates.add(new File(root + "Program Files (x86)/Steam/steamapps/common/ProjectZomboid"));
            candidates.add(new File(root + "Program Files/Steam/steamapps/common/ProjectZomboid"));
            candidates.add(new File(root + "Steam/steamapps/common/ProjectZomboid"));
        }
        candidates.add(new File(home(), ".steam/steam/steamapps/common/ProjectZomboid"));
        candidates.add(new File(home(), ".local/share/Steam/steamapps/common/ProjectZomboid"));
        return candidates.stream().filter(d -> new File(d, "media/scripts").isDirectory()).findFirst();
    }

    /** Zomboid's folder for local mods. */
    static File modsFolder() {
        return new File(home(), "Zomboid/mods");
    }
}
