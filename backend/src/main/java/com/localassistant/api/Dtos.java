package com.localassistant.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Contratos JSON de la API. */
public final class Dtos {

    private Dtos() {}

    public record ChatMessage(@NotBlank String role, @NotNull String content) {}

    /** Archivo o snippet adjuntado desde el navegador (el contenido viaja solo a localhost). */
    public record Attachment(@NotBlank String name, @NotNull String content) {}

    public record ChatRequest(
            @NotEmpty @Size(max = 500) List<@Valid ChatMessage> messages,
            @Size(max = 500) List<@Valid Attachment> attachments,
            String projectPath,
            String model) {}

    public record ScanRequest(@NotBlank String projectPath) {}
}
