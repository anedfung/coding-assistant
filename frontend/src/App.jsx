import { useCallback, useEffect, useRef, useState } from 'react';
import { getHealth, streamChat } from './api.js';
import { readFiles } from './files.js';
import ContextPanel from './components/ContextPanel.jsx';
import Message from './components/Message.jsx';

const SUGGESTIONS = [
  'Revisa este método y dime por qué lanza NullPointerException:',
  'Revisa el proyecto y busca posibles bugs en la capa de servicio.',
  'Refactoriza esta clase y entrégame el archivo completo corregido.',
];

// Solo se guardan preferencias (modelo y ruta); nunca el código ni las conversaciones.
const load = (key, fallback) => {
  try {
    return localStorage.getItem(key) ?? fallback;
  } catch {
    return fallback;
  }
};
const save = (key, value) => {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* almacenamiento no disponible */
  }
};

export default function App() {
  const [health, setHealth] = useState(null);
  const [model, setModel] = useState(() => load('assistant.model', ''));
  const [projectPath, setProjectPath] = useState(() => load('assistant.projectPath', ''));
  const [attachments, setAttachments] = useState([]);
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const abortRef = useRef(null);
  const bottomRef = useRef(null);

  const refreshHealth = useCallback(async () => {
    try {
      const h = await getHealth();
      setHealth(h);
      setModel((current) => current || h.defaultModel);
    } catch (e) {
      setHealth({ ollama: 'down', message: e.message, models: [] });
    }
  }, []);

  useEffect(() => {
    refreshHealth();
  }, [refreshHealth]);

  useEffect(() => save('assistant.model', model), [model]);
  useEffect(() => save('assistant.projectPath', projectPath), [projectPath]);
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: 'end' });
  }, [messages]);

  async function addFiles(fileList) {
    const { accepted, skipped } = await readFiles(fileList);
    setAttachments((prev) => {
      const byName = new Map(prev.map((a) => [a.name, a]));
      accepted.forEach((a) => byName.set(a.name, a));
      return [...byName.values()];
    });
    setNotice(
      skipped.length
        ? `${accepted.length} archivos adjuntados. Omitidos: ${skipped.slice(0, 5).join(', ')}${skipped.length > 5 ? '…' : ''}`
        : `${accepted.length} archivos adjuntados.`,
    );
  }

  async function send() {
    const text = input.trim();
    if (!text || busy) return;

    setError('');
    setNotice('');
    const history = [...messages, { role: 'user', content: text }];
    setMessages([...history, { role: 'assistant', content: '', streaming: true }]);
    setInput('');
    setBusy(true);

    const controller = new AbortController();
    abortRef.current = controller;
    let acc = '';
    let frame = 0;
    const patchLast = (patch) =>
      setMessages((prev) => {
        const copy = prev.slice();
        copy[copy.length - 1] = { ...copy[copy.length - 1], ...patch };
        return copy;
      });
    const flush = () => {
      frame = 0;
      patchLast({ content: acc });
    };

    try {
      await streamChat(
        {
          messages: history.map(({ role, content }) => ({ role, content })),
          attachments,
          projectPath: projectPath.trim() || null,
          model: model || null,
        },
        {
          signal: controller.signal,
          onMeta: (context) => patchLast({ context }),
          onToken: (token) => {
            acc += token;
            if (!frame) frame = requestAnimationFrame(flush);
          },
        },
      );
    } catch (e) {
      if (e.name !== 'AbortError') setError(e.message || 'Error desconocido.');
    } finally {
      if (frame) cancelAnimationFrame(frame);
      if (acc) {
        patchLast({ content: acc, streaming: false });
      } else {
        // Sin respuesta: se quita el turno fallido y se devuelve el texto al cuadro para reintentar.
        setMessages((prev) => prev.slice(0, -2));
        setInput(text);
      }
      setBusy(false);
      abortRef.current = null;
    }
  }

  function onKeyDown(event) {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      send();
    }
  }

  const ready = health?.ollama === 'up';
  const contextHint = [
    projectPath.trim() ? 'proyecto' : null,
    attachments.length ? `${attachments.length} adjunto${attachments.length === 1 ? '' : 's'}` : null,
  ]
    .filter(Boolean)
    .join(' + ');

  return (
    <div className="app">
      <ContextPanel
        health={health}
        model={model}
        setModel={setModel}
        projectPath={projectPath}
        setProjectPath={setProjectPath}
        attachments={attachments}
        onAddFiles={addFiles}
        onRemoveAttachment={(name) => setAttachments((prev) => prev.filter((a) => a.name !== name))}
        onClearAttachments={() => setAttachments([])}
        onRefresh={refreshHealth}
      />

      <main className="chat">
        <header className="chat-header">
          <div>
            <h1>Asistente de código</h1>
            <p className="sub">Procesado en esta máquina{model ? ` · ${model}` : ''}</p>
          </div>
          <button
            type="button"
            className="btn small"
            disabled={busy || messages.length === 0}
            onClick={() => {
              setMessages([]);
              setError('');
            }}
          >
            Nueva conversación
          </button>
        </header>

        <div className="messages">
          {messages.length === 0 ? (
            <div className="empty">
              <h2>¿Qué quieres revisar hoy?</h2>
              <p>
                Pega un fragmento de código, adjunta archivos o indica la ruta de un proyecto. Te devolveré archivos
                completos listos para copiar y pegar.
              </p>
              <div className="suggestions">
                {SUGGESTIONS.map((s) => (
                  <button key={s} type="button" className="chip" onClick={() => setInput(s)}>
                    {s}
                  </button>
                ))}
              </div>
            </div>
          ) : (
            messages.map((m, i) => <Message key={i} message={m} />)
          )}
          <div ref={bottomRef} />
        </div>

        {error && <div className="banner error">{error}</div>}
        {notice && !error && <div className="banner info">{notice}</div>}

        <form
          className="composer"
          onSubmit={(e) => {
            e.preventDefault();
            send();
          }}
        >
          <textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={onKeyDown}
            rows={4}
            placeholder="Escribe tu pregunta o pega código aquí…  (Enter envía · Shift+Enter nueva línea)"
            spellCheck={false}
          />
          <div className="composer-side">
            {contextHint && <span className="muted small-text">Contexto: {contextHint}</span>}
            {busy ? (
              <button type="button" className="btn danger" onClick={() => abortRef.current?.abort()}>
                Detener
              </button>
            ) : (
              <button type="submit" className="btn primary" disabled={!input.trim() || !ready}>
                Enviar
              </button>
            )}
          </div>
        </form>
      </main>
    </div>
  );
}
