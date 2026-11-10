import { Children, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import 'highlight.js/styles/github-dark.css';

function extractText(node) {
  if (node == null || typeof node === 'boolean') return '';
  if (typeof node === 'string' || typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map(extractText).join('');
  if (node.props) return extractText(node.props.children);
  return '';
}

async function copyToClipboard(text) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const area = document.createElement('textarea');
    area.value = text;
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    document.execCommand('copy');
    document.body.removeChild(area);
  }
}

function CodeBlock({ children }) {
  const [copied, setCopied] = useState(false);
  const codeElement = Children.toArray(children)[0];
  const className = codeElement?.props?.className || '';
  const language = /language-([\w+-]+)/.exec(className)?.[1] || 'texto';
  const text = extractText(codeElement?.props?.children).replace(/\n$/, '');

  async function copy() {
    await copyToClipboard(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 1800);
  }

  return (
    <div className="codeblock">
      <div className="codeblock-bar">
        <span className="codeblock-lang">{language}</span>
        <button type="button" className="copy-btn" onClick={copy}>
          {copied ? '¡Copiado!' : 'Copiar'}
        </button>
      </div>
      <pre>{children}</pre>
    </div>
  );
}

const components = { pre: CodeBlock };
const remarkPlugins = [remarkGfm];
const rehypePlugins = [[rehypeHighlight, { ignoreMissing: true }]];

export default function Markdown({ children }) {
  return (
    <div className="markdown">
      <ReactMarkdown remarkPlugins={remarkPlugins} rehypePlugins={rehypePlugins} components={components}>
        {children}
      </ReactMarkdown>
    </div>
  );
}
