'use strict';

// YouTube araması.
// - Metin araması resmi YouTube Data API v3 ile yapılır (YOUTUBE_API_KEY gerekir).
// - Arama kutusuna YouTube linki ya da 11 karakterlik video kimliği yazılırsa
//   anahtarsız oEmbed servisiyle tek video bulunur.

const API_TABANI = process.env.YOUTUBE_API_TABANI || 'https://www.googleapis.com/youtube/v3';
const OEMBED_TABANI = process.env.YOUTUBE_OEMBED_TABANI || 'https://www.youtube.com/oembed';
const BOLGE = process.env.YOUTUBE_BOLGE || 'TR';
const DIL = process.env.YOUTUBE_DIL || 'tr';
const ZAMAN_ASIMI = 10000;
const ONBELLEK_SURESI = 10 * 60 * 1000;
const ONBELLEK_SINIRI = 200;

const VIDEO_KIMLIGI = /^[A-Za-z0-9_-]{11}$/;

class AramaHatasi extends Error {
  constructor(mesaj, durum = 502, kod = 'YOUTUBE_HATASI') {
    super(mesaj);
    this.durum = durum;
    this.kod = kod;
  }
}

function anahtar() {
  return process.env.YOUTUBE_API_KEY || '';
}

// ---- Önbellek (aynı aramayı tekrar tekrar API'ye sormamak için; kota tasarrufu) ----
const onbellek = new Map();
function onbellektenAl(k) {
  const kayit = onbellek.get(k);
  if (!kayit) return undefined;
  if (Date.now() - kayit.zaman > ONBELLEK_SURESI) {
    onbellek.delete(k);
    return undefined;
  }
  return kayit.deger;
}
function onbellegeYaz(k, deger) {
  if (onbellek.size >= ONBELLEK_SINIRI) onbellek.delete(onbellek.keys().next().value);
  onbellek.set(k, { zaman: Date.now(), deger });
}

// ---- Yardımcılar ----

// Link ya da kimlikten video kimliğini çıkarır; değilse null.
function videoKimligiBul(metin) {
  const s = metin.trim();
  if (VIDEO_KIMLIGI.test(s)) return s;
  let url;
  try {
    url = new URL(/^https?:\/\//i.test(s) ? s : 'https://' + s);
  } catch {
    return null;
  }
  const host = url.hostname.replace(/^(www|m|music)\./, '');
  let id = null;
  if (host === 'youtu.be') id = url.pathname.split('/')[1];
  else if (host === 'youtube.com' || host === 'youtube-nocookie.com') {
    if (url.pathname === '/watch') id = url.searchParams.get('v');
    else {
      const m = url.pathname.match(/^\/(?:embed|shorts|live|v)\/([^/?#]+)/);
      if (m) id = m[1];
    }
  }
  return id && VIDEO_KIMLIGI.test(id) ? id : null;
}

// ISO 8601 süresini (PT1H2M14S) "1:02:14" biçimine çevirir.
function sureBicimle(iso) {
  const m = /^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/.exec(iso || '');
  if (!m) return '';
  const saat = Number(m[1] || 0) * 24 + Number(m[2] || 0);
  const dk = Number(m[3] || 0);
  const sn = Number(m[4] || 0);
  if (!saat && !dk && !sn) return ''; // canlı yayın
  const iki = (n) => String(n).padStart(2, '0');
  return saat ? `${saat}:${iki(dk)}:${iki(sn)}` : `${dk}:${iki(sn)}`;
}

function kucukResimSec(thumbnails) {
  const t = thumbnails || {};
  const secilen = t.medium || t.high || t.default || t.standard || t.maxres;
  return secilen && /^https:\/\//.test(secilen.url) ? secilen.url : '';
}

async function getJson(url, basliklar = {}) {
  let yanit;
  try {
    yanit = await fetch(url, { headers: basliklar, signal: AbortSignal.timeout(ZAMAN_ASIMI) });
  } catch (err) {
    throw new AramaHatasi('YouTube’a ulaşılamadı', 504);
  }
  const veri = await yanit.json().catch(() => ({}));
  return { durum: yanit.status, veri };
}

// ---- Data API ----

async function dataApi(yol, parametreler) {
  const url = new URL(API_TABANI + yol);
  for (const [k, v] of Object.entries(parametreler)) if (v !== undefined && v !== '') url.searchParams.set(k, v);
  const { durum, veri } = await getJson(url, { 'X-Goog-Api-Key': anahtar(), Accept: 'application/json' });
  if (durum >= 200 && durum < 300) return veri;

  const neden = veri && veri.error && veri.error.errors && veri.error.errors[0] && veri.error.errors[0].reason;
  if (neden === 'quotaExceeded' || neden === 'dailyLimitExceeded') {
    throw new AramaHatasi('YouTube API günlük kotası doldu', 429, 'KOTA_DOLDU');
  }
  if (durum === 400 || durum === 403) {
    console.error('YouTube API hatası:', durum, neden || (veri.error && veri.error.message));
    throw new AramaHatasi('YouTube API anahtarı geçersiz ya da yetkisiz', 502, 'ANAHTAR_GECERSIZ');
  }
  throw new AramaHatasi('YouTube araması başarısız (' + durum + ')');
}

async function metinAra(q, sayfa) {
  if (!anahtar()) {
    throw new AramaHatasi(
      'Arama için sunucuda YOUTUBE_API_KEY tanımlı değil. Yine de bir YouTube linki yapıştırarak video açabilirsin.',
      503,
      'ANAHTAR_YOK'
    );
  }
  const arama = await dataApi('/search', {
    part: 'snippet',
    type: 'video',
    maxResults: 20,
    q,
    pageToken: sayfa,
    regionCode: BOLGE,
    relevanceLanguage: DIL,
    safeSearch: 'moderate',
  });
  const kimlikler = (arama.items || [])
    .map((i) => i.id && i.id.videoId)
    .filter((id) => id && VIDEO_KIMLIGI.test(id));
  if (!kimlikler.length) return { sonuclar: [], sonrakiSayfa: null };

  // search.list süreyi vermez ve başlıkları HTML kaçışlı döndürür; videos.list ile tamamla.
  const detay = await dataApi('/videos', {
    part: 'snippet,contentDetails,statistics',
    id: kimlikler.join(','),
    hl: DIL,
  });
  const harita = new Map((detay.items || []).map((v) => [v.id, v]));
  const sonuclar = kimlikler
    .map((id) => harita.get(id))
    .filter(Boolean)
    .map((v) => {
      const sn = v.snippet || {};
      const yerel = sn.localized || {};
      return {
        id: v.id,
        baslik: yerel.title || sn.title || '',
        kanal: sn.channelTitle || '',
        sure: sureBicimle(v.contentDetails && v.contentDetails.duration),
        kucukResim: kucukResimSec(sn.thumbnails),
        izlenmeSayisi: v.statistics && v.statistics.viewCount ? Number(v.statistics.viewCount) : null,
        yayinTarihi: sn.publishedAt || null,
        canli: sn.liveBroadcastContent === 'live',
      };
    });
  return { sonuclar, sonrakiSayfa: arama.nextPageToken || null };
}

// ---- oEmbed (anahtarsız, tek video) ----

async function videoBilgisi(id) {
  const url = new URL(OEMBED_TABANI);
  url.searchParams.set('url', 'https://www.youtube.com/watch?v=' + id);
  url.searchParams.set('format', 'json');
  const { durum, veri } = await getJson(url);
  if (durum === 404 || durum === 400) throw new AramaHatasi('Video bulunamadı', 404, 'VIDEO_YOK');
  if (durum === 401 || durum === 403) throw new AramaHatasi('Bu video gömülü oynatmaya kapalı ya da gizli', 403, 'VIDEO_KAPALI');
  if (durum < 200 || durum >= 300) throw new AramaHatasi('Video bilgisi alınamadı (' + durum + ')');
  return {
    id,
    baslik: String(veri.title || ''),
    kanal: String(veri.author_name || ''),
    sure: '',
    kucukResim: `https://i.ytimg.com/vi/${id}/mqdefault.jpg`,
    izlenmeSayisi: null,
    yayinTarihi: null,
    canli: false,
  };
}

async function ara(q, sayfa) {
  const temiz = q.trim();
  const id = videoKimligiBul(temiz);
  const anahtarSozcuk = id ? 'id:' + id : 'q:' + temiz.toLocaleLowerCase('tr') + '|' + (sayfa || '');
  const onbellekte = onbellektenAl(anahtarSozcuk);
  if (onbellekte) return onbellekte;

  const sonuc = id ? { sonuclar: [await videoBilgisi(id)], sonrakiSayfa: null } : await metinAra(temiz, sayfa);
  onbellegeYaz(anahtarSozcuk, sonuc);
  return sonuc;
}

module.exports = { ara, AramaHatasi, VIDEO_KIMLIGI, videoKimligiBul, sureBicimle, anahtarVar: () => !!anahtar() };
