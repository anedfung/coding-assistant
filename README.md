# Asistente de código local

Chat de IA para código que corre **100% en tu computadora**: React (Vite + pnpm) → Spring Boot (Maven) → Ollama.
Tu código no sale de la máquina: no hay APIs en la nube, telemetría ni servicios externos.

```
Navegador (React)  ──►  Spring Boot  127.0.0.1:8080  ──►  Ollama  127.0.0.1:11434
   chat, adjuntos        arma el contexto, escanea            modelo de código
   botón "Copiar"        proyectos, hace streaming            (qwen2.5-coder, etc.)
```

## Requisitos

- Java 17 o superior y Maven 3.9+
- Node 18+ (recomendado 20) y pnpm
- Ollama instalado y corriendo

## Puesta en marcha

**1. Modelo** (única vez; es lo único que necesita internet)

```bash
ollama pull qwen2.5-coder:7b
```

**2. Backend**

```bash
cd backend
mvn spring-boot:run
```

**3. Frontend (modo desarrollo)**

```bash
cd frontend
pnpm install
pnpm dev
```

Abre http://localhost:5173

### Modo "un solo proceso" (sin Node en ejecución)

```bash
cd frontend && pnpm install && pnpm build   # deja la interfaz dentro del backend
cd ../backend && mvn package
java -jar target/local-code-assistant-0.1.0.jar
```

Abre http://localhost:8080

## Cómo darle código al asistente

| Forma | Cuándo usarla |
|---|---|
| **Pegar en el chat** | Snippets cortos. |
| **Adjuntos** (panel izquierdo → *Archivos* o *Carpeta*) | Unos pocos archivos concretos. Se envían en cada pregunta hasta que los quites. |
| **Ruta del proyecto** | Proyectos completos. El backend lee la carpeta en disco y elige los archivos que caben en el contexto, **priorizando los que nombres en tu pregunta** (por ejemplo "arregla `UserService`"). |

La **ruta del proyecto** se vuelve a leer del disco en cada mensaje, así que siempre ve la versión actual de tu
código (guarda el archivo en tu editor y pregunta de nuevo). Los **adjuntos**, en cambio, son una copia del momento en
que los eliges: si cambias el archivo, vuelve a adjuntarlo o usa la ruta.

Se respetan los `.gitignore` del proyecto (el de la raíz y los de subcarpetas), además de `node_modules`, `target`,
`.git`, etc. Para proyectos grandes usa la ruta del proyecto y no el selector *Carpeta*: el navegador lista todos los
archivos de la carpeta (incluido `node_modules`) antes de que se pueda filtrar nada.

El botón *Ver qué se incluiría* muestra qué archivos entrarían antes de preguntar. Bajo cada respuesta aparece el
resumen del contexto realmente enviado (archivos, recortados y omitidos).

Las respuestas piden archivos **completos** con la ruta en una línea propia y un botón **Copiar** en cada bloque. El
prompt del sistema está en `backend/src/main/resources/prompts/system.md`; puedes editarlo.

## Privacidad y seguridad

- El servidor escucha solo en `127.0.0.1`.
- Si `assistant.ollama.base-url` no apunta a esta máquina, **la aplicación se niega a arrancar**.
- El cliente HTTP ignora cualquier proxy del sistema: el tráfico hacia Ollama va directo a loopback.
- Se rechazan peticiones con cabecera `Host` u `Origin` no locales (protege contra DNS rebinding y contra páginas web
  que intenten llamar a tu API desde el navegador).
- En modo un solo proceso, la interfaz se sirve con una CSP que impide conectar con cualquier otro origen.
- Nunca se envían al modelo archivos sensibles (`.env*`, `*.pem`, `*.key`, `*.p12`, `*.jks`, `id_rsa`...). Tampoco
  `node_modules`, `target`, `.git`, lockfiles ni binarios.
- Las conversaciones viven solo en memoria del navegador. `localStorage` guarda únicamente el modelo y la ruta elegidos.
- El backend no registra el contenido de tus mensajes ni de tus archivos.
- Opcional: `assistant.scan.allowed-roots` limita qué carpetas pueden escanearse.

Recomendaciones fuera de este proyecto:

- No cambies `OLLAMA_HOST` para exponer Ollama en la red.
- Si tu versión de Ollama ofrece modelos "cloud" (sufijo `-cloud`), no los uses: se ejecutan en servidores remotos.

## Configuración (`backend/src/main/resources/application.yml`)

| Propiedad | Por defecto | Para qué sirve |
|---|---|---|
| `assistant.ollama.model` | `qwen2.5-coder:7b` | Modelo por defecto (también se elige en la interfaz). |
| `assistant.ollama.num-ctx` | `16384` | Ventana de contexto en tokens. Se fija explícitamente porque Ollama recorta en silencio con valores bajos. |
| `assistant.context.max-chars` | `28000` | Caracteres de código enviados por pregunta. |
| `assistant.scan.max-file-chars` | `16000` | Máximo por archivo; los más largos se envían recortados y se marcan. |
| `assistant.scan.allowed-roots` | vacío | Carpetas permitidas para escaneo. |
| `assistant.scan.respect-gitignore` | `true` | Excluye lo que indiquen los `.gitignore` del proyecto. |
| `assistant.scan.max-visited` | `20000` | Tope de archivos revisados por escaneo; si se supera, se detiene y avisa. |
| `server.port` | `8080` | Si lo cambias, actualiza también el proxy en `frontend/vite.config.js`. |

### Ajuste según tu hardware

Regla práctica: `max-chars ≈ num-ctx × 1.7`.

| `num-ctx` | `max-chars` | Cuándo |
|---|---|---|
| 8192 | ~14000 | Equipos con poca RAM. |
| 16384 | 28000 | Por defecto. |
| 32768 | ~56000 | Si tienes RAM/VRAM de sobra. |

Como orden de magnitud, un modelo de 7B ocupa unos 5 GB, uno de 14B unos 9 GB y uno de 32B unos 20 GB, más la memoria del
contexto. Con poca RAM prueba `qwen2.5-coder:3b`; con más capacidad, `qwen2.5-coder:14b` o `32b`.

## Solución de problemas

| Síntoma | Qué hacer |
|---|---|
| Panel muestra "Sin conexión" | Comprueba que Ollama corre (`ollama serve`) y responde en `http://127.0.0.1:11434`. |
| Error 404 / "model not found" | `ollama pull <modelo>`. |
| `mvn` no descarga dependencias | Tu institución puede exigir un mirror interno de Maven: configúralo en `~/.m2/settings.xml`. Igual con `pnpm config set registry <url>`. |
| Error 403 "Host no permitido" | Entra por `localhost` o `127.0.0.1`, no por el nombre de la máquina ni su IP. |
| Ignora partes del código | Revisa "recortado/omitido" bajo la respuesta; menciona el archivo por nombre, sube `num-ctx` y `max-chars`, o adjunta solo lo relevante. |
| Respuestas muy lentas | Usa un modelo más pequeño o menos `num-ctx`; con GPU compatible Ollama la usa automáticamente. |
| Puerto 8080 ocupado | Cambia `server.port` y el `target` del proxy en `vite.config.js`. |

## Estructura

```
backend/   Spring Boot 3 (Java 17, solo spring-boot-starter-web y validation)
  config/  propiedades, guardia de loopback, filtro Host/Origin, streaming
  ollama/  cliente de Ollama con java.net.http (sin dependencias extra)
  scan/    escáner de proyectos (filtros, archivos sensibles, presupuesto de contexto)
  chat/    armado del prompt y del contexto
  api/     endpoints: POST /api/chat (NDJSON), POST /api/scan, GET /api/health
frontend/  React 18 + Vite 5 + pnpm (react-markdown, rehype-highlight)
```
