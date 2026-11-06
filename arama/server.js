'use strict';

// Arama sitesi — Express sunucusu ve geçmiş/ayarlar API'si.
// Çalıştırma: npm start
// Ortam değişkenleri: PORT (varsayılan 3000), HOST (varsayılan 0.0.0.0),
//                     VERI_DOSYASI (varsayılan ./data/veri.json)

const path = require('path');
const express = require('express');
const { Depo, VARSAYILAN_AYARLAR } = require('./depo');

const PORT = Number(process.env.PORT) || 3000;
const HOST = process.env.HOST || '0.0.0.0';
const VERI_DOSYASI = process.env.VERI_DOSYASI || path.join(__dirname, 'data', 'veri.json');
const PUBLIC_DIR = path.join(__dirname, 'public');

const depo = new Depo(VERI_DOSYASI);
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

// ---------------- Doğrulama yardımcıları ----------------

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

// ---------------- API ----------------

const api = express.Router();
api.use(express.json({ limit: '10kb' }));

api.get('/saglik', (req, res) => {
  res.json({ durum: 'ok', calismaSuresi: Math.round(process.uptime()) });
});

// Geçmiş
api.get('/gecmis', (req, res) => {
  res.json(depo.gecmis());
});

api.post('/gecmis', async (req, res) => {
  const govde = req.body || {};
  if (!depo.ayarlar().gecmisiKaydet) {
    return res.status(409).json({ hata: 'Geçmiş kaydı ayarlardan kapatılmış' });
  }
  const kayit = {
    id: metin(govde.id, 'id', { zorunlu: true, enFazla: 64 }),
    baslik: metin(govde.baslik, 'baslik', { zorunlu: true }),
    kanal: metin(govde.kanal, 'kanal'),
    sure: metin(govde.sure, 'sure', { enFazla: 16 }),
  };
  if (govde.ilerleme !== undefined) {
    const p = Number(govde.ilerleme);
    if (!Number.isFinite(p) || p < 0 || p > 100) throw new IstekHatasi('"ilerleme" 0-100 arası bir sayı olmalı');
    kayit.ilerleme = Math.round(p);
  }
  for (const k of Object.keys(kayit)) if (kayit[k] === undefined) delete kayit[k];
  res.status(201).json(await depo.gecmiseEkle(kayit));
});

api.delete('/gecmis', async (req, res) => {
  await depo.gecmisiTemizle();
  res.status(204).end();
});

api.delete('/gecmis/:id', async (req, res) => {
  const silindi = await depo.gecmistenSil(req.params.id);
  if (!silindi) return res.status(404).json({ hata: 'Kayıt bulunamadı' });
  res.status(204).end();
});

// Ayarlar
const AYAR_DOGRULAYICI = {
  tema: (v) => {
    if (!['sistem', 'acik', 'koyu'].includes(v)) throw new IstekHatasi('"tema" sistem, acik ya da koyu olmalı');
    return v;
  },
  kullaniciAdi: (v) => metin(v, 'kullaniciAdi', { zorunlu: true, enFazla: 32 }),
};
for (const [ad, deger] of Object.entries(VARSAYILAN_AYARLAR)) {
  if (typeof deger === 'boolean') {
    AYAR_DOGRULAYICI[ad] = (v) => {
      if (typeof v !== 'boolean') throw new IstekHatasi(`"${ad}" true ya da false olmalı`);
      return v;
    };
  }
}

api.get('/ayarlar', (req, res) => {
  res.json(depo.ayarlar());
});

api.patch('/ayarlar', async (req, res) => {
  const govde = req.body;
  if (!govde || typeof govde !== 'object' || Array.isArray(govde)) throw new IstekHatasi('Gövde bir JSON nesnesi olmalı');
  const degisiklik = {};
  for (const [ad, deger] of Object.entries(govde)) {
    const dogrula = AYAR_DOGRULAYICI[ad];
    if (!dogrula) throw new IstekHatasi(`Bilinmeyen ayar: "${ad}"`);
    degisiklik[ad] = dogrula(deger);
  }
  res.json(await depo.ayarlariGuncelle(degisiklik));
});

api.use((req, res) => res.status(404).json({ hata: 'Bulunamadı' }));

// API hata yakalayıcı (Express 5 async hataları buraya iletir)
api.use((err, req, res, next) => {
  if (err instanceof IstekHatasi) return res.status(err.durum).json({ hata: err.message });
  if (err.type === 'entity.parse.failed') return res.status(400).json({ hata: 'Geçersiz JSON' });
  if (err.type === 'entity.too.large') return res.status(413).json({ hata: 'İstek çok büyük' });
  console.error(err);
  res.status(500).json({ hata: 'Sunucu hatası' });
});

app.use('/api', api);

// ---------------- Statik site ----------------

app.use(express.static(PUBLIC_DIR, {
  setHeaders(res, dosya) {
    res.set('Cache-Control', dosya.endsWith('.html') ? 'no-cache' : 'public, max-age=3600');
  },
}));

// Uzantısız bilinmeyen yollar tek sayfalık uygulamaya düşer.
app.get('/{*yol}', (req, res, next) => {
  if (path.extname(req.path)) return next();
  res.sendFile(path.join(PUBLIC_DIR, 'index.html'));
});

app.use((req, res) => res.status(404).type('text').send('Bulunamadı'));

// ---------------- Başlat ----------------

async function baslat() {
  await depo.yukle();
  const server = app.listen(PORT, HOST, () => {
    console.log(`Arama sunucusu çalışıyor: http://localhost:${PORT}`);
  });

  const kapat = () => {
    console.log('Sunucu kapatılıyor...');
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(1), 5000).unref();
  };
  process.on('SIGINT', kapat);
  process.on('SIGTERM', kapat);
}

if (require.main === module) {
  baslat().catch((err) => {
    console.error('Sunucu başlatılamadı:', err);
    process.exit(1);
  });
}

module.exports = { app, depo };
