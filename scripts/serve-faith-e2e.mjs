// Serves a built SPA and proxies to an explicitly supplied, isolated local API.
// Never point this harness at a production database/API.
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
const target = new URL(
  process.env.FAITH_E2E_API_URL ?? 'http://127.0.0.1:18085',
);
if (
  target.protocol !== 'http:' ||
  !['127.0.0.1', 'localhost'].includes(target.hostname)
)
  throw new Error('Faith E2E requires an isolated loopback API');
const root = resolve('dist/apps/client/browser');
const mime = {
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
};
http
  .createServer(async (req, res) => {
    if (req.url.startsWith('/api/')) {
      const upstream = http.request(
        new URL(req.url, target),
        { method: req.method, headers: req.headers },
        (response) => {
          res.writeHead(response.statusCode, response.headers);
          response.pipe(res);
        },
      );
      upstream.on('error', () => {
        res.writeHead(502);
        res.end('Isolated API unavailable');
      });
      req.pipe(upstream);
      return;
    }
    const path = resolve(root, '.' + decodeURIComponent(req.url.split('?')[0]));
    if (path !== root && !path.startsWith(root + sep)) {
      res.writeHead(403);
      res.end();
      return;
    }
    try {
      const data = await readFile(path);
      res.setHeader(
        'Content-Type',
        mime[extname(path)] ?? 'application/octet-stream',
      );
      res.end(data);
    } catch {
      try {
        res.setHeader('Content-Type', 'text/html');
        res.end(await readFile(resolve(root, 'index.html')));
      } catch {
        res.writeHead(503);
        res.end('Build client first');
      }
    }
  })
  .listen(4305, '127.0.0.1');
