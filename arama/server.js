'use strict';

// Arama sitesi için bağımlılıksız Node.js sunucusu.
// Çalıştırma: node server.js   (ya da npm start)
// Ortam değişkenleri: PORT (varsayılan 3000), HOST (varsayılan 0.0.0.0)

const http = require('http');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const PORT = Number(process.env.PORT) || 3000;
const HOST = process.env.HOST || '0.0.0.0';
const PUBLIC_DIR = path.join(__dirname, 'public');

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
  '.woff2': 'font/woff2',
};
const COMPRESSIBLE = new Set(['.html', '.css', '.js', '.json', '.svg', '.txt']);

const SECURITY_HEADERS = {
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
  'X-Frame-Options': 'SAMEORIGIN',
};

function send(res, status, body, headers = {}) {
  res.writeHead(status, { ...SECURITY_HEADERS, ...headers });
  res.end(body);
}

function sendJson(res, status, data) {
  send(res, status, JSON.stringify(data), { 'Content-Type': MIME['.json'], 'Cache-Control': 'no-store' });
}

// İstek yolunu public/ içinde güvenli bir dosya yoluna çevirir (dizin dışına çıkmayı engeller).
function resolvePath(urlPath) {
  let decoded;
  try {
    decoded = decodeURIComponent(urlPath);
  } catch {
    return null;
  }
  if (decoded.includes('\0')) return null;
  const filePath = path.normalize(path.join(PUBLIC_DIR, decoded));
  if (filePath !== PUBLIC_DIR && !filePath.startsWith(PUBLIC_DIR + path.sep)) return null;
  return filePath;
}

function serveFile(req, res, filePath, stat) {
  const ext = path.extname(filePath).toLowerCase();
  const etag = `W/"${stat.size.toString(16)}-${Math.floor(stat.mtimeMs).toString(16)}"`;
  const headers = {
    'Content-Type': MIME[ext] || 'application/octet-stream',
    'Cache-Control': ext === '.html' ? 'no-cache' : 'public, max-age=3600',
    'Last-Modified': stat.mtime.toUTCString(),
    ETag: etag,
  };

  if (req.headers['if-none-match'] === etag) {
    return send(res, 304, null, headers);
  }

  const acceptsGzip = /\bgzip\b/.test(req.headers['accept-encoding'] || '');
  const useGzip = acceptsGzip && COMPRESSIBLE.has(ext) && stat.size > 1024;
  if (useGzip) {
    headers['Content-Encoding'] = 'gzip';
    headers['Vary'] = 'Accept-Encoding';
  } else {
    headers['Content-Length'] = stat.size;
  }

  res.writeHead(200, { ...SECURITY_HEADERS, ...headers });
  if (req.method === 'HEAD') return res.end();

  const stream = fs.createReadStream(filePath);
  stream.on('error', () => res.destroy());
  if (useGzip) stream.pipe(zlib.createGzip()).pipe(res);
  else stream.pipe(res);
}

function serveStatic(req, res, pathname) {
  const filePath = resolvePath(pathname);
  if (!filePath) return send(res, 400, 'Geçersiz istek', { 'Content-Type': MIME['.txt'] });

  fs.stat(filePath, (err, stat) => {
    if (!err && stat.isDirectory()) {
      const index = path.join(filePath, 'index.html');
      return fs.stat(index, (e2, s2) => {
        if (e2) return notFound(req, res);
        serveFile(req, res, index, s2);
      });
    }
    if (err || !stat.isFile()) return notFound(req, res);
    serveFile(req, res, filePath, stat);
  });
}

// Uzantısız yollar tek sayfalık uygulamaya (index.html) düşer; diğerleri 404.
function notFound(req, res) {
  const pathname = new URL(req.url, 'http://x').pathname;
  if (!path.extname(pathname)) {
    const index = path.join(PUBLIC_DIR, 'index.html');
    return fs.stat(index, (err, stat) => {
      if (err) return send(res, 404, 'Bulunamadı', { 'Content-Type': MIME['.txt'] });
      serveFile(req, res, index, stat);
    });
  }
  send(res, 404, 'Bulunamadı', { 'Content-Type': MIME['.txt'] });
}

const server = http.createServer((req, res) => {
  const start = Date.now();
  res.on('finish', () => {
    console.log(`${new Date().toISOString()} ${req.method} ${req.url} ${res.statusCode} ${Date.now() - start}ms`);
  });

  let url;
  try {
    url = new URL(req.url, 'http://localhost');
  } catch {
    return send(res, 400, 'Geçersiz istek', { 'Content-Type': MIME['.txt'] });
  }

  if (url.pathname === '/api/saglik') {
    return sendJson(res, 200, { durum: 'ok', calismaSuresi: Math.round(process.uptime()) });
  }

  if (req.method !== 'GET' && req.method !== 'HEAD') {
    return send(res, 405, 'İzin verilmeyen yöntem', { Allow: 'GET, HEAD', 'Content-Type': MIME['.txt'] });
  }

  serveStatic(req, res, url.pathname);
});

server.listen(PORT, HOST, () => {
  console.log(`Arama sunucusu çalışıyor: http://localhost:${PORT}`);
});

function shutdown() {
  console.log('Sunucu kapatılıyor...');
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 5000).unref();
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
