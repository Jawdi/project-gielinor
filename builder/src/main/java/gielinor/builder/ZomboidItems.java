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
 * Reads Project Zomboid's scripts (`media/scripts/**.txt`): nested blocks like
 * `module Base { item Axe { Weight = 3.0, ... } model Axe { mesh = ...,
 * attachment world { ... } } }`. The builder copies vanilla items and their
 * models as templates.
 */
final class ZomboidItems {
    /** A block: its header (`item Axe`), properties in order, and child blocks. */
    record Block(String header, Map<String, String> properties, List<Block> children) {
        String kind() {
            int space = header.indexOf(' ');
            return space < 0 ? header : header.substring(0, space);
        }

        String name() {
            int space = header.indexOf(' ');
            return space < 0 ? "" : header.substring(space + 1).trim();
        }

        /** The block written back out as script text, indented by `indent` spaces. */
        String text(int indent) {
            String pad = " ".repeat(indent);
            StringBuilder out = new StringBuilder(pad + header + "\n" + pad + "{\n");
            properties.forEach((k, v) -> out.append(pad).append("    ").append(k).append(" = ").append(v).append(",\n"));
            for (Block child : children) {
                out.append(child.text(indent + 4));
            }
            return out.append(pad).append("}\n").toString();
        }
    }

    /** Vanilla items and models in the `Base` module, by name. */
    record Scripts(Map<String, Block> items, Map<String, Block> models) {
    }

    private ZomboidItems() {
    }

    static Scripts load(File install) throws IOException {
        Map<String, Block> items = new HashMap<>();
        Map<String, Block> models = new HashMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(install.toPath().resolve("media/scripts"))) {
            files = walk.filter(p -> p.toString().endsWith(".txt")).sorted().toList();
        }
        for (Path file : files) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            for (Block module : parse(stripComments(text))) {
                if (!module.kind().equals("module") || !module.name().equals("Base")) {
                    continue;
                }
                for (Block b : module.children()) {
                    switch (b.kind()) {
                        case "item" -> items.put(b.name(), b);
                        case "model" -> models.put(b.name(), b);
                        default -> {
                        }
                    }
                }
            }
        }
        return new Scripts(items, models);
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

    /** The top-level blocks of a script file. */
    static List<Block> parse(String text) {
        List<String> tokens = tokens(text);
        List<Block> top = new ArrayList<>();
        parseInto(tokens, 0, top);
        return top;
    }

    /** Parses blocks from `start` into `out` until a closing brace; returns the index after it. */
    private static int parseInto(List<String> tokens, int start, List<Block> out) {
        int i = start;
        while (i < tokens.size()) {
            String t = tokens.get(i);
            if (t.equals("}")) {
                return i + 1;
            }
            if (i + 1 < tokens.size() && tokens.get(i + 1).equals("{") && !t.contains("=")) {
                Map<String, String> props = new LinkedHashMap<>();
                List<Block> children = new ArrayList<>();
                int j = i + 2;
                while (j < tokens.size() && !tokens.get(j).equals("}")) {
                    String u = tokens.get(j);
                    if (j + 1 < tokens.size() && tokens.get(j + 1).equals("{") && !u.contains("=")) {
                        List<Block> one = new ArrayList<>();
                        j = parseOne(tokens, j, one);
                        children.addAll(one);
                    } else {
                        int eq = u.indexOf('=');
                        if (eq > 0) {
                            props.put(u.substring(0, eq).trim(), u.substring(eq + 1).trim());
                        }
                        j++;
                    }
                }
                out.add(new Block(t, props, children));
                i = j + 1;
            } else {
                i++;
            }
        }
        return i;
    }

    private static int parseOne(List<String> tokens, int start, List<Block> out) {
        List<String> slice = new ArrayList<>();
        int depth = 0;
        int i = start;
        for (; i < tokens.size(); i++) {
            String t = tokens.get(i);
            slice.add(t);
            if (t.equals("{")) {
                depth++;
            } else if (t.equals("}") && --depth == 0) {
                break;
            }
        }
        parseInto(slice, 0, out);
        return i + 1;
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
