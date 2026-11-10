package com.localassistant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Defensa para un servicio que solo vive en localhost:
 * - Rechaza cualquier cabecera Host que no sea local (protege contra DNS rebinding).
 * - Rechaza peticiones con Origin que no sea local (protege contra páginas web externas
 *   que intenten llamar a la API desde tu navegador).
 * - Añade cabeceras de seguridad y una CSP que impide que la interfaz conecte con terceros.
 */
@Component
public class LocalOnlyFilter extends OncePerRequestFilter {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private static final String CSP = "default-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!LOCAL_HOSTS.contains(hostname(request.getHeader("Host")))) {
            deny(response, "Host no permitido.");
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !isLocalOrigin(origin)) {
            deny(response, "Origen no permitido.");
            return;
        }

        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (!request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Content-Security-Policy", CSP);
        }
        chain.doFilter(request, response);
    }

    private static String hostname(String hostHeader) {
        if (hostHeader == null) {
            return "";
        }
        String h = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 ? h.substring(0, end + 1) : h;
        }
        int colon = h.lastIndexOf(':');
        return colon >= 0 ? h.substring(0, colon) : h;
    }

    private static boolean isLocalOrigin(String origin) {
        try {
            String host = URI.create(origin).getHost();
            return host != null && LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void deny(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
