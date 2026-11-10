package com.localassistant.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración del asistente (prefijo "assistant" en application.yml).
 */
@ConfigurationProperties(prefix = "assistant")
public record AssistantProperties(
        @DefaultValue Ollama ollama,
        @DefaultValue Context context,
        @DefaultValue Scan scan) {

    public record Ollama(
            @DefaultValue("http://127.0.0.1:11434") String baseUrl,
            @DefaultValue("qwen2.5-coder:7b") String model,
            @DefaultValue("16384") int numCtx,
            @DefaultValue("0.2") double temperature,
            @DefaultValue("900") int readTimeoutSeconds) {}

    public record Context(
            @DefaultValue("28000") int maxChars,
            @DefaultValue("16") int maxHistoryMessages) {}

    public record Scan(
            @DefaultValue List<String> allowedRoots,
            @DefaultValue("400") int maxFiles,
            @DefaultValue("256") int maxFileKb,
            @DefaultValue("16000") int maxFileChars,
            @DefaultValue({
                ".git", ".svn", ".idea", ".vscode", "node_modules", "target", "build", "dist", "out",
                "bin", "obj", ".gradle", ".mvn", "__pycache__", ".venv", "venv", ".next", ".nuxt",
                "coverage", ".pytest_cache"
            }) List<String> ignoredDirs,
            @DefaultValue({
                "java", "kt", "kts", "groovy", "scala", "py", "js", "jsx", "mjs", "cjs", "ts", "tsx",
                "vue", "svelte", "json", "xml", "yml", "yaml", "properties", "toml", "ini", "cfg", "conf",
                "gradle", "md", "txt", "html", "htm", "css", "scss", "sass", "less", "sql", "sh", "bat",
                "ps1", "c", "h", "cpp", "hpp", "cs", "go", "rs", "php", "rb", "swift", "dart", "lua"
            }) List<String> extensions,
            @DefaultValue("true") boolean respectGitignore,
            @DefaultValue("20000") int maxVisited) {}
}
