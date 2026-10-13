package gielinor.builder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads the vanilla items out of Project Zomboid's scripts
 * (`media/scripts/**.txt`), keeping each `item Name { Key = Value, ... }`
 * block's properties in order. Only what the builder needs: nested blocks
 * inside an item are skipped.
 */
final class ZomboidItems {
    private ZomboidItems() {
    }

    /** Every item in the `Base` module, by type name. */
    static Map<String, Map<String, String>> load(File install) throws IOException {
        Map<String, Map<String, String>> items = new HashMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(install.toPath().resolve("media/scripts"))) {
            files = walk.filter(p -> p.toString().endsWith(".txt")).sorted().toList();
        }
        for (Path file : files) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            parseModule(stripComments(text), items);
        }
        return items;
    }

    static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 2;
            } else if (text.startsWith("//", i)) {
                int end = text.indexOf('\n', i);
                i = end < 0 ? text.length() : end;
            } else {
                out.append(text.charAt(i++));
            }
        }
        return out.toString();
    }

    /** Finds `module Base { ... item X { ... } ... }` and collects its items. */
    static void parseModule(String text, Map<String, Map<String, String>> into) {
        List<String> tokens = tokens(text);
        String module = null;
        int depth = 0;
        int moduleDepth = -1;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("{")) {
                depth++;
            } else if (t.equals("}")) {
                depth--;
                if (depth <= moduleDepth) {
                    module = null;
                    moduleDepth = -1;
                }
            } else if (depth == 0 && t.startsWith("module ")) {
                module = t.substring(7).trim();
                moduleDepth = depth;
            } else if (module != null && module.equals("Base") && depth == moduleDepth + 1 && t.startsWith("item ")
                    && i + 1 < tokens.size() && tokens.get(i + 1).equals("{")) {
                String name = t.substring(5).trim();
                Map<String, String> props = new LinkedHashMap<>();
                int inner = 0;
                int j = i + 1;
                for (; j < tokens.size(); j++) {
                    String u = tokens.get(j);
                    if (u.equals("{")) {
                        inner++;
                    } else if (u.equals("}")) {
                        if (--inner == 0) {
                            break;
                        }
                    } else if (inner == 1) {
                        int eq = u.indexOf('=');
                        if (eq > 0) {
                            props.put(u.substring(0, eq).trim(), u.substring(eq + 1).trim());
                        }
                    }
                }
                into.put(name, props);
                i = j;
            }
        }
    }

    /** Braces as tokens of their own; everything else split at commas and line ends. */
    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        Runnable flush = () -> {
            String s = cur.toString().trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
            cur.setLength(0);
        };
        for (char c : text.toCharArray()) {
            if (c == '{' || c == '}') {
                flush.run();
                out.add(String.valueOf(c));
            } else if (c == ',' || c == '\n' || c == '\r') {
                flush.run();
            } else {
                cur.append(c);
            }
        }
        flush.run();
        return out;
    }
}
