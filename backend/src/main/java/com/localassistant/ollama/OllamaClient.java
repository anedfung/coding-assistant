package com.localassistant.ollama;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localassistant.config.AssistantProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Cliente mínimo de la API de Ollama usando solo java.net.http (sin dependencias extra).
 * Se desactiva explícitamente cualquier proxy del sistema: el tráfico va directo a loopback.
 */
@Component
public class OllamaClient {

    private final AssistantProperties.Ollama cfg;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OllamaClient(AssistantProperties props, ObjectMapper mapper) {
        this.cfg = props.ollama();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(ProxySelector.of((InetSocketAddress) null))
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Lista los modelos instalados en Ollama. */
    public List<String> listModels() {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/tags"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new OllamaException("Ollama respondió con HTTP " + response.statusCode() + " al listar modelos.");
            }
            List<String> names = new ArrayList<>();
            for (JsonNode model : mapper.readTree(response.body()).path("models")) {
                names.add(model.path("name").asText());
            }
            return names;
        } catch (IOException e) {
            throw unreachable(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OllamaException("Solicitud interrumpida.", e);
        }
    }

    /**
     * Envía la conversación y entrega cada fragmento de texto a {@code onToken} a medida que llega.
     * Si {@code onToken} lanza una excepción (p. ej. el navegador cerró la conexión), se cierra el
     * stream hacia Ollama y la generación se detiene.
     */
    public void streamChat(String model, List<Map<String, String>> messages, Consumer<String> onToken) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages,
                "stream", true,
                "options", Map.of("num_ctx", cfg.numCtx(), "temperature", cfg.temperature()));

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri("/api/chat"))
                    .timeout(Duration.ofSeconds(cfg.readTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar la petición.", e);
        }

        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw unreachable(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OllamaException("Solicitud interrumpida.", e);
        }

        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new OllamaException(errorMessage(response.statusCode(), in.readAllBytes(), model));
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = mapper.readTree(line);
                if (node.hasNonNull("error")) {
                    throw new OllamaException(node.get("error").asText());
                }
                String piece = node.path("message").path("content").asText("");
                if (!piece.isEmpty()) {
                    onToken.accept(piece);
                }
                if (node.path("done").asBoolean(false)) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new OllamaException("Se interrumpió la conexión con Ollama: " + e.getMessage(), e);
        }
    }

    private String errorMessage(int status, byte[] rawBody, String model) {
        String detail = "";
        try {
            JsonNode json = mapper.readTree(rawBody);
            detail = json.path("error").asText("");
        } catch (IOException ignored) {
            // cuerpo no JSON
        }
        StringBuilder message = new StringBuilder("Ollama respondió HTTP ").append(status);
        if (!detail.isBlank()) {
            message.append(": ").append(detail);
        }
        if (status == 404) {
            message.append(" · Instala el modelo con: ollama pull ").append(model);
        }
        return message.toString();
    }

    private OllamaException unreachable(IOException cause) {
        return new OllamaException(
                "No se pudo conectar con Ollama en " + cfg.baseUrl() + ". ¿Está corriendo (ollama serve)?", cause);
    }

    private URI uri(String path) {
        String base = cfg.baseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }
}
