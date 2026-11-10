async function jsonOrThrow(res) {
  if (res.ok) return res.json();
  let message = `Error ${res.status}`;
  try {
    const body = await res.json();
    if (body.error) message = body.error;
  } catch {
    if (res.status >= 500) {
      message = 'No se pudo contactar con el backend. ¿Está corriendo en el puerto 8080?';
    }
  }
  throw new Error(message);
}

export const getHealth = () => fetch('/api/health').then(jsonOrThrow);

export const scanProject = (projectPath) =>
  fetch('/api/scan', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ projectPath }),
  }).then(jsonOrThrow);

/**
 * Chat en streaming. El backend responde con NDJSON:
 *   {"type":"meta","context":{...}}  {"type":"token","content":"..."}  {"type":"done"}  {"type":"error","message":"..."}
 */
export async function streamChat(payload, { onMeta, onToken, signal }) {
  const res = await fetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
    signal,
  });
  if (!res.ok) await jsonOrThrow(res);

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  const handle = (line) => {
    if (!line.trim()) return;
    const event = JSON.parse(line);
    if (event.type === 'meta') onMeta?.(event.context);
    else if (event.type === 'token') onToken?.(event.content);
    else if (event.type === 'error') throw new Error(event.message);
  };

  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let newline;
    while ((newline = buffer.indexOf('\n')) >= 0) {
      handle(buffer.slice(0, newline));
      buffer = buffer.slice(newline + 1);
    }
  }
  handle(buffer);
}
