// Lectura de archivos en el navegador. El contenido solo se envía al backend local (127.0.0.1).

const IGNORED_DIRS = new Set([
  '.git', '.svn', '.idea', '.vscode', 'node_modules', 'target', 'build', 'dist', 'out', 'bin', 'obj',
  '.gradle', '.mvn', '__pycache__', '.venv', 'venv', '.next', '.nuxt', 'coverage', '.pytest_cache',
]);

const EXTENSIONS = new Set([
  'java', 'kt', 'kts', 'groovy', 'scala', 'py', 'js', 'jsx', 'mjs', 'cjs', 'ts', 'tsx', 'vue', 'svelte',
  'json', 'xml', 'yml', 'yaml', 'properties', 'toml', 'ini', 'cfg', 'conf', 'gradle', 'md', 'txt', 'html',
  'htm', 'css', 'scss', 'sass', 'less', 'sql', 'sh', 'bat', 'ps1', 'c', 'h', 'cpp', 'hpp', 'cs', 'go', 'rs',
  'php', 'rb', 'swift', 'dart', 'lua',
]);

const SENSITIVE_NAMES = new Set([
  'id_rsa', 'id_dsa', 'id_ecdsa', 'id_ed25519', 'credentials', 'credentials.json', 'secrets.json',
  '.npmrc', '.pypirc', '.netrc',
]);
const SENSITIVE_SUFFIXES = ['.pem', '.key', '.p12', '.pfx', '.jks', '.keystore', '.kdbx'];
const NOISE_SUFFIXES = ['.min.js', '.min.css', '.map', '.lock', '-lock.json', '-lock.yaml', '-lock.yml'];

const MAX_FILE_BYTES = 256 * 1024;
const MAX_FILES = 300;
const MAX_TOTAL_BYTES = 3 * 1024 * 1024;

function isSensitive(name) {
  const lower = name.toLowerCase();
  return SENSITIVE_NAMES.has(lower) || lower.startsWith('.env') || SENSITIVE_SUFFIXES.some((s) => lower.endsWith(s));
}

function isWantedInFolder(name) {
  const lower = name.toLowerCase();
  if (lower === 'dockerfile' || lower === 'makefile') return true;
  if (NOISE_SUFFIXES.some((s) => lower.endsWith(s))) return false;
  const dot = lower.lastIndexOf('.');
  return dot >= 0 && EXTENSIONS.has(lower.slice(dot + 1));
}

/**
 * Lee archivos elegidos con el selector (individuales o una carpeta completa).
 * En carpetas se aplican los mismos filtros que en el backend; los archivos elegidos uno a uno
 * se aceptan siempre (salvo los sensibles).
 */
export async function readFiles(fileList) {
  const accepted = [];
  const skipped = [];
  let totalBytes = 0;

  for (const file of Array.from(fileList)) {
    const path = file.webkitRelativePath || file.name;
    const parts = path.split('/');
    const name = parts[parts.length - 1];
    const fromFolder = Boolean(file.webkitRelativePath);

    if (parts.slice(0, -1).some((dir) => IGNORED_DIRS.has(dir))) continue;
    if (isSensitive(name)) {
      skipped.push(`${path} (sensible)`);
      continue;
    }
    if (fromFolder && !isWantedInFolder(name)) continue;
    if (file.size > MAX_FILE_BYTES) {
      skipped.push(`${path} (demasiado grande)`);
      continue;
    }
    if (accepted.length >= MAX_FILES || totalBytes + file.size > MAX_TOTAL_BYTES) {
      skipped.push(`${path} (límite de adjuntos)`);
      continue;
    }
    const content = await file.text();
    if (content.includes('\u0000')) {
      skipped.push(`${path} (binario)`);
      continue;
    }
    totalBytes += file.size;
    accepted.push({ name: path, content });
  }
  return { accepted, skipped };
}
