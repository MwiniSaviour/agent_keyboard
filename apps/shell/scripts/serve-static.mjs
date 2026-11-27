// Zero-dependency static server for previewing the exported bundle (`out/`) exactly as the
// WebView will load it. Not used in production (the app serves assets from the APK).
import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { extname, join, normalize, resolve } from 'node:path';

const root = resolve(process.argv[2] ?? 'out');
const port = Number(process.argv[3] ?? 3000);
const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.txt': 'text/plain', '.woff2': 'font/woff2', '.webmanifest': 'application/manifest+json',
};

async function resolveFile(urlPath) {
  const safe = normalize(decodeURIComponent(urlPath.split('?')[0])).replace(/^(\.\.[/\\])+/, '');
  let file = join(root, safe);
  try {
    if ((await stat(file)).isDirectory()) file = join(file, 'index.html');
    return file;
  } catch {
    try { await stat(file + '.html'); return file + '.html'; } catch { return join(root, '404.html'); }
  }
}

createServer(async (req, res) => {
  const file = await resolveFile(req.url ?? '/');
  try {
    const body = await readFile(file);
    const immutable = file.includes('/_next/static/');
    res.writeHead(file.endsWith('404.html') ? 404 : 200, {
      'Content-Type': TYPES[extname(file)] ?? 'application/octet-stream',
      'Cache-Control': immutable ? 'public, max-age=31536000, immutable' : 'no-cache',
    });
    res.end(body);
  } catch {
    res.writeHead(404).end('Not found');
  }
}).listen(port, '0.0.0.0', () => console.log(`Serving ${root} on http://0.0.0.0:${port}`));
