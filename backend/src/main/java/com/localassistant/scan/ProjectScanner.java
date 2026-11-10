package com.localassistant.scan;

import com.localassistant.config.AssistantProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Recorre una carpeta de proyecto en disco local y elige qué archivos caben en el contexto del modelo.
 * - Ignora carpetas de dependencias/compilación, lo que indiquen los .gitignore del proyecto y los binarios.
 * - Nunca incluye archivos sensibles (.env, claves privadas, keystores...).
 * - Prioriza los archivos cuyo nombre aparece mencionado en la pregunta del usuario.
 * - Lee el disco en cada llamada: siempre ve la versión actual del código.
 */
@Service
public class ProjectScanner {

    /** Sobrecosto aproximado (en caracteres) de la etiqueta que envuelve cada archivo. */
    public static final int FILE_OVERHEAD = 80;

    private static final int MIN_USEFUL_CHARS = 200;
    private static final int SKIPPED_SAMPLE_LIMIT = 30;

    private static final Set<String> SENSITIVE_NAMES = Set.of(
            "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "credentials", "credentials.json",
            "secrets.json", ".npmrc", ".pypirc", ".netrc");
    private static final List<String> SENSITIVE_SUFFIXES =
            List.of(".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".kdbx");
    private static final List<String> NOISE_SUFFIXES =
            List.of(".min.js", ".min.css", ".map", ".lock", "-lock.json", "-lock.yaml", "-lock.yml");

    public record ScannedFile(String path, String content, boolean truncated) {}

    public record ScanResult(List<ScannedFile> files, List<String> skippedSample, int skippedTotal) {}

    private final AssistantProperties.Scan cfg;

    public ProjectScanner(AssistantProperties props) {
        this.cfg = props.scan();
    }

    /**
     * @param projectPath carpeta (o archivo) local a escanear
     * @param hint        texto del usuario; los archivos nombrados en él se incluyen primero
     * @param budgetChars caracteres disponibles para este escaneo
     */
    public ScanResult scan(String projectPath, String hint, int budgetChars) {
        Path root = resolveRoot(projectPath);
        List<Path> candidates = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        AtomicInteger skippedTotal = new AtomicInteger();
        Set<String> ignoredDirs = Set.copyOf(cfg.ignoredDirs());
        Set<String> extensions = cfg.extensions().stream()
                .map(e -> e.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        long maxBytes = cfg.maxFileKb() * 1024L;

        AtomicInteger visited = new AtomicInteger();
        boolean useGitignore = cfg.respectGitignore();
        // Pila de .gitignore de las carpetas por las que se va bajando (de la raíz a la más profunda).
        Deque<GitignoreRules> gitRules = new ArrayDeque<>();
        Deque<Boolean> pushed = new ArrayDeque<>();

        try {
            if (Files.isRegularFile(root)) {
                if (isSensitive(root.getFileName().toString())) {
                    throw new IllegalArgumentException("Ese archivo está marcado como sensible y no se envía al modelo.");
                }
                candidates.add(root);
            } else {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (!dir.equals(root)
                                && (ignoredDirs.contains(dir.getFileName().toString())
                                        || isGitIgnored(gitRules, dir, true))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        GitignoreRules rules = useGitignore ? GitignoreRules.load(dir) : null;
                        if (rules != null) {
                            gitRules.addLast(rules);
                        }
                        pushed.addLast(rules != null);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                        if (pushed.removeLast()) {
                            gitRules.removeLast();
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (!attrs.isRegularFile()) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (visited.incrementAndGet() > cfg.maxVisited()) {
                            skip(skipped, skippedTotal, "Escaneo detenido tras revisar " + cfg.maxVisited()
                                    + " archivos. Apunta a una subcarpeta o revisa tu .gitignore.");
                            return FileVisitResult.TERMINATE;
                        }
                        String name = file.getFileName().toString();
                        String rel = relative(root, file);
                        if (isSensitive(name)) {
                            skip(skipped, skippedTotal, rel + " (sensible, nunca se envía al modelo)");
                        } else if (isGitIgnored(gitRules, file, false)) {
                            // Excluido por .gitignore: no cuenta como omitido.
                            return FileVisitResult.CONTINUE;
                        } else if (isWanted(name, extensions)) {
                            if (attrs.size() > maxBytes) {
                                skip(skipped, skippedTotal, rel + " (demasiado grande)");
                            } else if (candidates.size() >= cfg.maxFiles()) {
                                skippedTotal.incrementAndGet();
                            } else {
                                candidates.add(file);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el proyecto: " + e.getMessage(), e);
        }

        String hintLower = hint == null ? "" : hint.toLowerCase(Locale.ROOT);
        candidates.sort(Comparator.<Path>comparingInt(p -> mentioned(p, hintLower) ? 0 : 1)
                .thenComparingInt(p -> root.relativize(p).getNameCount())
                .thenComparing(Path::toString));

        List<ScannedFile> selected = new ArrayList<>();
        int used = 0;
        int index = 0;
        for (; index < candidates.size(); index++) {
            int remaining = budgetChars - used - FILE_OVERHEAD;
            if (remaining < MIN_USEFUL_CHARS) {
                break;
            }
            Path path = candidates.get(index);
            String rel = relative(root, path);
            String text = readText(path);
            if (text == null) {
                skip(skipped, skippedTotal, rel + " (no es texto UTF-8)");
                continue;
            }
            int limit = Math.min(cfg.maxFileChars(), remaining);
            boolean truncated = text.length() > limit;
            if (truncated) {
                text = text.substring(0, limit);
            }
            selected.add(new ScannedFile(rel, text, truncated));
            used += text.length() + FILE_OVERHEAD;
        }
        for (; index < candidates.size(); index++) {
            skip(skipped, skippedTotal, relative(root, candidates.get(index)) + " (sin espacio en el contexto)");
        }
        return new ScanResult(selected, skipped, skippedTotal.get());
    }

    private Path resolveRoot(String raw) {
        String cleaned = raw.trim();
        if (cleaned.length() >= 2 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        if (cleaned.equals("~") || cleaned.startsWith("~/") || cleaned.startsWith("~\\")) {
            cleaned = System.getProperty("user.home") + cleaned.substring(1);
        }
        Path root;
        try {
            root = Paths.get(cleaned).toRealPath();
        } catch (InvalidPathException | IOException e) {
            throw new IllegalArgumentException("La ruta no existe o no es accesible: " + cleaned);
        }
        List<String> allowed = cfg.allowedRoots();
        if (!allowed.isEmpty()) {
            boolean ok = false;
            for (String candidate : allowed) {
                try {
                    if (root.startsWith(Paths.get(candidate).toRealPath())) {
                        ok = true;
                        break;
                    }
                } catch (IOException | InvalidPathException ignored) {
                    // carpeta permitida inexistente: se ignora
                }
            }
            if (!ok) {
                throw new IllegalArgumentException(
                        "La ruta está fuera de las carpetas permitidas (assistant.scan.allowed-roots).");
            }
        }
        return root;
    }

    /** Aplica los .gitignore de la pila de la raíz hacia abajo; la regla más profunda y última gana. */
    private static boolean isGitIgnored(Deque<GitignoreRules> stack, Path path, boolean directory) {
        int decision = GitignoreRules.NO_MATCH;
        for (GitignoreRules rules : stack) {
            int result = rules.match(path, directory);
            if (result != GitignoreRules.NO_MATCH) {
                decision = result;
            }
        }
        return decision == GitignoreRules.IGNORED;
    }

    private static void skip(List<String> sample, AtomicInteger total, String message) {
        total.incrementAndGet();
        if (sample.size() < SKIPPED_SAMPLE_LIMIT) {
            sample.add(message);
        }
    }

    private static String relative(Path root, Path file) {
        if (file.equals(root)) {
            return file.getFileName().toString();
        }
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static boolean isSensitive(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (SENSITIVE_NAMES.contains(lower) || lower.startsWith(".env")) {
            return true;
        }
        for (String suffix : SENSITIVE_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWanted(String name, Set<String> extensions) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("dockerfile") || lower.equals("makefile")) {
            return true;
        }
        for (String noise : NOISE_SUFFIXES) {
            if (lower.endsWith(noise)) {
                return false;
            }
        }
        int dot = lower.lastIndexOf('.');
        return dot >= 0 && extensions.contains(lower.substring(dot + 1));
    }

    private static boolean mentioned(Path path, String hintLower) {
        if (hintLower.isEmpty()) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (hintLower.contains(name)) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.length() >= 4 && hintLower.contains(base);
    }

    private static String readText(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            for (byte b : bytes) {
                if (b == 0) {
                    return null;
                }
            }
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (text.startsWith("﻿")) {
                text = text.substring(1);
            }
            return text.replace("\r\n", "\n");
        } catch (IOException e) {
            return null;
        }
    }
}
