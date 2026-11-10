import { useState } from 'react';
import Markdown from './Markdown.jsx';

function ContextSummary({ context }) {
  const total = context.files.length;
  if (!total && !context.skippedTotal) return null;
  return (
    <details className="ctx">
      <summary>
        Contexto enviado al modelo local: {total} archivo{total === 1 ? '' : 's'} ·{' '}
        {context.usedChars.toLocaleString()} / {context.budgetChars.toLocaleString()} caracteres
        {context.skippedTotal ? ` · ${context.skippedTotal} omitidos` : ''}
      </summary>
      {total > 0 && (
        <ul>
          {context.files.map((f, i) => (
            <li key={`${f.path}-${i}`}>
              {f.path}
              {f.truncated ? ' (recortado)' : ''} <small>· {f.source}</small>
            </li>
          ))}
        </ul>
      )}
      {context.skippedSample.length > 0 && (
        <>
          <p className="ctx-title">Omitidos</p>
          <ul>
            {context.skippedSample.map((s, i) => (
              <li key={`${s}-${i}`}>{s}</li>
            ))}
          </ul>
        </>
      )}
    </details>
  );
}

export default function Message({ message }) {
  const isUser = message.role === 'user';
  const [copied, setCopied] = useState(false);

  async function copyAll() {
    try {
      await navigator.clipboard.writeText(message.content);
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    } catch {
      /* el portapapeles no está disponible */
    }
  }

  return (
    <article className={`msg ${isUser ? 'msg-user' : 'msg-assistant'}`}>
      <div className="msg-head">
        <span className="msg-role">{isUser ? 'Tú' : 'Asistente'}</span>
        {!isUser && message.content && !message.streaming && (
          <button type="button" className="link-btn" onClick={copyAll}>
            {copied ? 'Copiado' : 'Copiar respuesta'}
          </button>
        )}
      </div>
      <div className="msg-body">
        {isUser ? (
          <div className="user-text">{message.content}</div>
        ) : message.content ? (
          <Markdown>{message.content}</Markdown>
        ) : (
          <span className="thinking">Pensando…</span>
        )}
      </div>
      {!isUser && message.context && <ContextSummary context={message.context} />}
    </article>
  );
}
