import { useRef, useState } from 'react';
import { scanProject } from '../api.js';

function formatSize(chars) {
  return chars >= 1024 ? `${(chars / 1024).toFixed(1)} KB` : `${chars} B`;
}

export default function ContextPanel({
  health,
  model,
  setModel,
  projectPath,
  setProjectPath,
  attachments,
  onAddFiles,
  onRemoveAttachment,
  onClearAttachments,
  onRefresh,
}) {
  const filesInput = useRef(null);
  const folderInput = useRef(null);
  const [preview, setPreview] = useState(null);
  const [previewError, setPreviewError] = useState('');
  const [scanning, setScanning] = useState(false);

  const up = health?.ollama === 'up';
  const models = health?.models ?? [];
  const modelMissing = up && model && !models.includes(model);

  async function runPreview() {
    setPreview(null);
    setPreviewError('');
    setScanning(true);
    try {
      setPreview(await scanProject(projectPath.trim()));
    } catch (e) {
      setPreviewError(e.message);
    } finally {
      setScanning(false);
    }
  }

  function handlePicked(event) {
    const files = event.target.files;
    if (files && files.length) onAddFiles(files);
    event.target.value = '';
  }

  return (
    <aside className="sidebar">
      <div className="brand">
        <span className="brand-mark">{'</>'}</span>
        <div>
          <strong>Code Assistant</strong>
          <small>100% local</small>
        </div>
      </div>

      <section className="panel">
        <h2>Ollama</h2>
        {!health && <p className="muted">Comprobando…</p>}
        {health && (
          <p className={`status ${up ? 'ok' : 'bad'}`}>
            <span className="dot" />
            {up ? 'Conectado' : 'Sin conexión'}
          </p>
        )}
        {health && !up && <p className="hint">{health.message}</p>}

        <label className="field">
          <span>Modelo</span>
          {models.length > 0 ? (
            <select value={model} onChange={(e) => setModel(e.target.value)}>
              {!models.includes(model) && model && <option value={model}>{model} (no instalado)</option>}
              {models.map((m) => (
                <option key={m} value={m}>
                  {m}
                </option>
              ))}
            </select>
          ) : (
            <input value={model} onChange={(e) => setModel(e.target.value)} placeholder="qwen2.5-coder:7b" />
          )}
        </label>
        {modelMissing && (
          <p className="hint">
            Ese modelo no está instalado. Ejecuta en una terminal: <code>ollama pull {model}</code>
          </p>
        )}
        <button type="button" className="btn small" onClick={onRefresh}>
          Reintentar conexión
        </button>
      </section>

      <section className="panel">
        <h2>Proyecto en disco</h2>
        <label className="field">
          <span>Ruta de la carpeta</span>
          <input
            value={projectPath}
            onChange={(e) => {
              setProjectPath(e.target.value);
              setPreview(null);
              setPreviewError('');
            }}
            placeholder="C:\proyectos\mi-app  o  /home/yo/mi-app"
            spellCheck={false}
          />
        </label>
        <p className="muted small-text">
          El backend lee la carpeta localmente y elige los archivos que caben en el contexto, priorizando los que
          nombres en tu pregunta.
        </p>
        <button type="button" className="btn small" disabled={!projectPath.trim() || scanning} onClick={runPreview}>
          {scanning ? 'Escaneando…' : 'Ver qué se incluiría'}
        </button>
        {previewError && <p className="hint error-text">{previewError}</p>}
        {preview && (
          <details className="preview" open>
            <summary>
              {preview.files.length} archivos · {preview.usedChars.toLocaleString()} /{' '}
              {preview.budgetChars.toLocaleString()} caracteres
              {preview.skippedTotal ? ` · ${preview.skippedTotal} omitidos` : ''}
            </summary>
            <ul>
              {preview.files.map((f) => (
                <li key={f.path}>
                  {f.path} <small>{formatSize(f.chars)}{f.truncated ? ' · recortado' : ''}</small>
                </li>
              ))}
            </ul>
            {preview.skippedSample.length > 0 && (
              <>
                <p className="ctx-title">Omitidos</p>
                <ul className="skipped">
                  {preview.skippedSample.map((s) => (
                    <li key={s}>{s}</li>
                  ))}
                </ul>
              </>
            )}
          </details>
        )}
      </section>

      <section className="panel">
        <h2>Adjuntos</h2>
        <div className="row">
          <button type="button" className="btn small" onClick={() => filesInput.current?.click()}>
            Archivos
          </button>
          <button type="button" className="btn small" onClick={() => folderInput.current?.click()}>
            Carpeta
          </button>
        </div>
        <input ref={filesInput} type="file" multiple hidden onChange={handlePicked} />
        <input ref={folderInput} type="file" hidden webkitdirectory="" directory="" multiple onChange={handlePicked} />

        {attachments.length === 0 ? (
          <p className="muted small-text">
            Para fragmentos cortos, pega el código en el chat. Para proyectos completos usa la ruta de arriba: el
            selector «Carpeta» del navegador lista todos los archivos (incluido node_modules) antes de filtrar, y los
            adjuntos son una copia fija del momento en que los eliges.
          </p>
        ) : (
          <>
            <ul className="attachments">
              {attachments.map((a) => (
                <li key={a.name}>
                  <span title={a.name}>{a.name}</span>
                  <small>{formatSize(a.content.length)}</small>
                  <button type="button" className="icon-btn" aria-label={`Quitar ${a.name}`} onClick={() => onRemoveAttachment(a.name)}>
                    ×
                  </button>
                </li>
              ))}
            </ul>
            <button type="button" className="link-btn" onClick={onClearAttachments}>
              Quitar todos
            </button>
          </>
        )}
      </section>

      <footer className="privacy">
        Sin telemetría ni servicios externos. El servidor solo escucha en 127.0.0.1 y habla únicamente con tu Ollama
        local.
      </footer>
    </aside>
  );
}
