package com.localassistant.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localassistant.api.Dtos.ChatRequest;
import com.localassistant.api.Dtos.ScanRequest;
import com.localassistant.chat.ChatService;
import com.localassistant.chat.ChatService.ContextInfo;
import com.localassistant.chat.ChatService.PreparedChat;
import com.localassistant.config.AssistantProperties;
import com.localassistant.ollama.OllamaClient;
import com.localassistant.ollama.OllamaException;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final ChatService chatService;
    private final OllamaClient ollama;
    private final AssistantProperties props;
    private final ObjectMapper mapper;

    public ChatController(ChatService chatService, OllamaClient ollama, AssistantProperties props, ObjectMapper mapper) {
        this.chatService = chatService;
        this.ollama = ollama;
        this.props = props;
        this.mapper = mapper;
    }

    /**
     * Respuesta en streaming, un objeto JSON por línea (NDJSON):
     * {"type":"meta",...} una vez, luego {"type":"token","content":"..."} y por último {"type":"done"}.
     */
    @PostMapping("/chat")
    public ResponseEntity<StreamingResponseBody> chat(@Valid @RequestBody ChatRequest request) {
        // El armado del contexto ocurre antes de empezar a transmitir, así los errores de ruta salen como 400.
        PreparedChat prepared = chatService.prepare(request);
        StreamingResponseBody body = out -> stream(prepared, out);
        return ResponseEntity.ok()
                .contentType(NDJSON)
                .header("Cache-Control", "no-store")
                .body(body);
    }

    @PostMapping("/scan")
    public ContextInfo scan(@Valid @RequestBody ScanRequest request) {
        return chatService.preview(request.projectPath());
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        String defaultModel = props.ollama().model();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("defaultModel", defaultModel);
        try {
            List<String> models = ollama.listModels();
            out.put("ollama", "up");
            out.put("models", models);
            out.put("defaultModelInstalled",
                    models.contains(defaultModel) || (!defaultModel.contains(":") && models.contains(defaultModel + ":latest")));
        } catch (OllamaException e) {
            out.put("ollama", "down");
            out.put("message", e.getMessage());
            out.put("models", List.of());
        }
        return out;
    }

    private void stream(PreparedChat prepared, OutputStream out) throws IOException {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        try {
            emit(writer, Map.of("type", "meta", "context", prepared.context()));
            ollama.streamChat(prepared.model(), prepared.messages(),
                    token -> emit(writer, Map.of("type", "token", "content", token)));
            emit(writer, Map.of("type", "done"));
        } catch (OllamaException e) {
            try {
                emit(writer, Map.of("type", "error", "message", e.getMessage()));
            } catch (UncheckedIOException ignored) {
                // el navegador ya cerró la conexión
            }
        } catch (UncheckedIOException e) {
            // el navegador cerró la conexión (botón Detener): se corta también la generación en Ollama
        }
    }

    private void emit(Writer writer, Map<String, Object> event) {
        try {
            writer.write(mapper.writeValueAsString(event));
            writer.write('\n');
            writer.flush();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
