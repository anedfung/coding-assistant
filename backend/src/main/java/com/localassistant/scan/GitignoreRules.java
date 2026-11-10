package com.localassistant.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reglas de un archivo .gitignore, aplicables a las rutas dentro de su carpeta.
 *
 * Cubre lo habitual: comentarios, negación (!), patrones anclados (/x y a/b), solo carpetas (x/),
 * y los comodines *, ?, [abc] y **. Gana la última regla que coincide, como en git.
 * No cubre casos exóticos (espacios finales escapados, clases POSIX como [[:alpha:]]).
 */
final class GitignoreRules {

    static final int NO_MATCH = 0;
    static final int IGNORED = 1;
    static final int REINCLUDED = -1;

    private record Rule(Pattern pattern, boolean negated, boolean dirOnly, boolean anchored) {}

    private final Path base;
    private final List<Rule> rules;

    private GitignoreRules(Path base, List<Rule> rules) {
        this.base = base;
        this.rules = rules;
    }

    /** Reglas del .gitignore de {@code dir}, o null si no existe, está vacío o no se puede leer. */
    static GitignoreRules load(Path dir) {
        Path file = dir.resolve(".gitignore");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        String text;
        try {
            text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        List<Rule> parsed = new ArrayList<>();
        for (String line : text.split("\\R")) {
            Rule rule = parse(line);
            if (rule != null) {
                parsed.add(rule);
            }
        }
        return parsed.isEmpty() ? null : new GitignoreRules(dir, parsed);
    }

    /** {@link #IGNORED}, {@link #REINCLUDED} (negada con !) o {@link #NO_MATCH}. */
    int match(Path path, boolean isDirectory) {
        String relative = base.relativize(path).toString().replace('\\', '/');
        String name = path.getFileName().toString();
        int result = NO_MATCH;
        for (Rule rule : rules) {
            if (rule.dirOnly() && !isDirectory) {
                continue;
            }
            String subject = rule.anchored() ? relative : name;
            if (rule.pattern().matcher(subject).matches()) {
                result = rule.negated() ? REINCLUDED : IGNORED;
            }
        }
        return result;
    }

    private static Rule parse(String raw) {
        String line = raw.stripTrailing();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }
        boolean negated = false;
        if (line.startsWith("!")) {
            negated = true;
            line = line.substring(1);
        } else if (line.startsWith("\\#") || line.startsWith("\\!")) {
            line = line.substring(1);
        }
        boolean dirOnly = line.endsWith("/");
        if (dirOnly) {
            line = line.substring(0, line.length() - 1);
        }
        // Con una barra en cualquier otra posición, el patrón se ancla a la carpeta del .gitignore.
        boolean anchored = line.contains("/");
        if (line.startsWith("/")) {
            line = line.substring(1);
        }
        if (line.isEmpty()) {
            return null;
        }
        try {
            return new Rule(Pattern.compile(globToRegex(line)), negated, dirOnly, anchored);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        int n = glob.length();
        while (i < n) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < n && glob.charAt(i + 1) == '*') {
                        boolean atSegmentStart = i == 0 || glob.charAt(i - 1) == '/';
                        if (atSegmentStart && i + 2 < n && glob.charAt(i + 2) == '/') {
                            regex.append("(?:.*/)?"); // "**/": cero o más carpetas
                            i += 3;
                        } else if (atSegmentStart && i + 2 == n) {
                            regex.append(".*"); // "/**" al final: todo lo de dentro
                            i += 2;
                        } else {
                            regex.append("[^/]*");
                            i += 2;
                        }
                    } else {
                        regex.append("[^/]*");
                        i++;
                    }
                }
                case '?' -> {
                    regex.append("[^/]");
                    i++;
                }
                case '[' -> {
                    int end = glob.indexOf(']', i + 2);
                    if (end < 0) {
                        regex.append("\\[");
                        i++;
                    } else {
                        String body = glob.substring(i + 1, end);
                        if (body.startsWith("!")) {
                            body = "^" + body.substring(1);
                        }
                        regex.append('[').append(body.replace("\\", "\\\\")).append(']');
                        i = end + 1;
                    }
                }
                case '\\' -> {
                    if (i + 1 < n) {
                        appendLiteral(regex, glob.charAt(i + 1));
                        i += 2;
                    } else {
                        regex.append("\\\\");
                        i++;
                    }
                }
                default -> {
                    appendLiteral(regex, c);
                    i++;
                }
            }
        }
        return regex.toString();
    }

    private static void appendLiteral(StringBuilder regex, char c) {
        if ("\\.[]{}()*+-?^$|".indexOf(c) >= 0) {
            regex.append('\\');
        }
        regex.append(c);
    }
}
