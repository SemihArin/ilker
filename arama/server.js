'use strict';

// Arama sitesi — Express sunucusu ve geçmiş/ayarlar API'si.
// Çalıştırma: npm start
// Ortam değişkenleri: PORT (varsayılan 3000), HOST (varsayılan 0.0.0.0),
//                     VERI_DOSYASI (varsayılan ./data/veri.json)
//                     YOUTUBE_API_KEY (metin araması için YouTube Data API v3 anahtarı)

const path = require('path');
const express = require('express');
const { Depo, VARSAYILAN_AYARLAR, VIDEO_KIMLIGI } = require('./depo');
const yt = require('./yt');

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

function videoKimligi(deger) {
  const id = metin(deger, 'id', { zorunlu: true, enFazla: 11 });
  if (!VIDEO_KIMLIGI.test(id)) throw new IstekHatasi('"id" geçerli bir YouTube video kimliği olmalı');
  return id;
}

// İlerleme (0-100) ve konum (saniye) alanlarını doğrular.
function ilerlemeAlanlari(govde, kayit) {
  if (govde.ilerleme !== undefined) {
    const p = Number(govde.ilerleme);
    if (!Number.isFinite(p) || p < 0 || p > 100) throw new IstekHatasi('"ilerleme" 0-100 arası bir sayı olmalı');
    kayit.ilerleme = Math.round(p);
  }
  if (govde.konum !== undefined) {
    const k = Number(govde.konum);
    if (!Number.isFinite(k) || k < 0 || k > 864000) throw new IstekHatasi('"konum" geçerli bir saniye değeri olmalı');
    kayit.konum = Math.floor(k);
  }
  return kayit;
}

// ---------------- API ----------------

const api = express.Router();
api.use(express.json({ limit: '10kb' }));

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

// Geçmiş
api.get('/gecmis', (req, res) => {
  res.json(depo.gecmis());
});

api.post('/gecmis', async (req, res) => {
  const govde = req.body || {};
  if (!depo.ayarlar().gecmisiKaydet) {
    return res.status(409).json({ hata: 'Geçmiş kaydı ayarlardan kapatılmış' });
  }
  const kayit = ilerlemeAlanlari(govde, {
    id: videoKimligi(govde.id),
    baslik: metin(govde.baslik, 'baslik', { zorunlu: true }),
    kanal: metin(govde.kanal, 'kanal'),
    sure: metin(govde.sure, 'sure', { enFazla: 16 }),
    kucukResim: metin(govde.kucukResim, 'kucukResim', { enFazla: 300 }),
  });
  if (kayit.kucukResim && !/^https:\/\/i\d?\.ytimg\.com\//.test(kayit.kucukResim)) {
    throw new IstekHatasi('"kucukResim" bir YouTube küçük resim adresi olmalı');
  }
  for (const k of Object.keys(kayit)) if (kayit[k] === undefined) delete kayit[k];
  res.status(201).json(await depo.gecmiseEkle(kayit));
});

api.delete('/gecmis', async (req, res) => {
  await depo.gecmisiTemizle();
  res.status(204).end();
});

// İzlerken ilerlemeyi günceller; kaydın sırası değişmez.
api.patch('/gecmis/:id', async (req, res) => {
  const govde = req.body || {};
  const id = req.params.id;
  if (!depo.gecmis().some((k) => k.id === id)) return res.status(404).json({ hata: 'Kayıt bulunamadı' });
  if (!depo.ayarlar().gecmisiKaydet) {
    return res.status(409).json({ hata: 'Geçmiş kaydı ayarlardan kapatılmış' });
  }
  const kayit = ilerlemeAlanlari(govde, { id });
  const sure = metin(govde.sure, 'sure', { enFazla: 16 });
  if (sure) kayit.sure = sure;
  res.json(await depo.gecmiseEkle(kayit, { tasi: false }));
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

if (require.main === module) {
  baslat().catch((err) => {
    console.error('Sunucu başlatılamadı:', err);
    process.exit(1);
  });
}

module.exports = { app, depo };
