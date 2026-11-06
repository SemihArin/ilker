'use strict';

// Arama sitesi — Express sunucusu.
// Siteyi yayınlar ve YouTube aramasını yapar. Kullanıcı verileri (geçmiş, ayarlar)
// sunucuda tutulmaz; tarayıcının localStorage'ında kalır.
//
// Çalıştırma: npm start
// Ortam değişkenleri: PORT (varsayılan 3000), HOST (varsayılan 0.0.0.0),
//                     YOUTUBE_API_KEY (metin araması için YouTube Data API v3 anahtarı)

const path = require('path');
const express = require('express');
const yt = require('./yt');

const PORT = Number(process.env.PORT) || 3000;
const HOST = process.env.HOST || '0.0.0.0';
const PUBLIC_DIR = path.join(__dirname, 'public');

const app = express();

app.disable('x-powered-by');
app.use((req, res, next) => {
  res.set({
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'strict-origin-when-cross-origin',
    'X-Frame-Options': 'SAMEORIGIN',
  });
  next();
});
app.use((req, res, next) => {
  const bas = Date.now();
  res.on('finish', () => {
    console.log(`${new Date().toISOString()} ${req.method} ${req.originalUrl} ${res.statusCode} ${Date.now() - bas}ms`);
  });
  next();
});

// ---------------- API ----------------

class IstekHatasi extends Error {
  constructor(mesaj, durum = 400) {
    super(mesaj);
    this.durum = durum;
  }
}

function metin(deger, alan, { zorunlu = false, enFazla = 200 } = {}) {
  if (deger === undefined || deger === null || deger === '') {
    if (zorunlu) throw new IstekHatasi(`"${alan}" zorunlu`);
    return undefined;
  }
  if (typeof deger !== 'string') throw new IstekHatasi(`"${alan}" metin olmalı`);
  const kirpik = deger.trim();
  if (zorunlu && !kirpik) throw new IstekHatasi(`"${alan}" boş olamaz`);
  if (kirpik.length > enFazla) throw new IstekHatasi(`"${alan}" en fazla ${enFazla} karakter olabilir`);
  return kirpik;
}

const api = express.Router();

api.get('/saglik', (req, res) => {
  res.json({ durum: 'ok', calismaSuresi: Math.round(process.uptime()), aramaAnahtari: yt.anahtarVar() });
});

// Arama: ?q=metin ya da YouTube linki, &sayfa=sonrakiSayfa değeri
api.get('/ara', async (req, res) => {
  const q = metin(req.query.q, 'q', { zorunlu: true, enFazla: 200 });
  const sayfa = metin(req.query.sayfa, 'sayfa', { enFazla: 100 });
  try {
    res.set('Cache-Control', 'private, max-age=300');
    res.json(await yt.ara(q, sayfa));
  } catch (err) {
    if (err instanceof yt.AramaHatasi) {
      res.set('Cache-Control', 'no-store');
      return res.status(err.durum).json({ hata: err.message, kod: err.kod });
    }
    throw err;
  }
});

api.use((req, res) => res.status(404).json({ hata: 'Bulunamadı' }));

// API hata yakalayıcı (Express 5 async hataları buraya iletir)
api.use((err, req, res, next) => {
  if (err instanceof IstekHatasi) return res.status(err.durum).json({ hata: err.message });
  console.error(err);
  res.status(500).json({ hata: 'Sunucu hatası' });
});

app.use('/api', api);

// ---------------- Statik site ----------------

app.use(express.static(PUBLIC_DIR, {
  setHeaders(res, dosya) {
    res.set('Cache-Control', dosya.endsWith('.html') || dosya.endsWith('.js') ? 'no-cache' : 'public, max-age=3600');
  },
}));

// Uzantısız bilinmeyen yollar tek sayfalık uygulamaya düşer.
app.get('/{*yol}', (req, res, next) => {
  if (path.extname(req.path)) return next();
  res.sendFile(path.join(PUBLIC_DIR, 'index.html'));
});

app.use((req, res) => res.status(404).type('text').send('Bulunamadı'));

// ---------------- Başlat ----------------

if (require.main === module) {
  const server = app.listen(PORT, HOST, () => {
    console.log(`Arama sunucusu çalışıyor: http://localhost:${PORT}`);
    if (!yt.anahtarVar()) {
      console.log('Uyarı: YOUTUBE_API_KEY tanımlı değil; metin araması kapalı, yalnızca YouTube linkleri açılabilir.');
    }
  });

  const kapat = () => {
    console.log('Sunucu kapatılıyor...');
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(1), 5000).unref();
  };
  process.on('SIGINT', kapat);
  process.on('SIGTERM', kapat);
}

module.exports = { app };
