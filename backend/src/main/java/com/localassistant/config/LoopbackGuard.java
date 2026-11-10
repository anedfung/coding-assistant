package com.localassistant.config;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Garantía de privacidad: la aplicación se niega a arrancar si la URL de Ollama
 * no apunta a esta misma máquina (loopback). Así el código nunca puede enviarse
 * a otro host por un error de configuración.
 */
@Component
public class LoopbackGuard {

    private static final Logger log = LoggerFactory.getLogger(LoopbackGuard.class);

    public LoopbackGuard(AssistantProperties props) {
        String baseUrl = props.ollama().baseUrl();
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("assistant.ollama.base-url no es una URL válida: " + baseUrl, e);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalStateException("assistant.ollama.base-url debe ser http://host:puerto, y es: " + baseUrl);
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (!address.isLoopbackAddress()) {
                    throw new IllegalStateException(
                            "Por privacidad, Ollama debe estar en esta misma máquina (127.0.0.1/localhost). "
                                    + "Host configurado: " + uri.getHost());
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalStateException("No se pudo resolver el host de Ollama: " + uri.getHost(), e);
        }
        log.info("Ollama verificado en loopback: {}", baseUrl);
    }
}
