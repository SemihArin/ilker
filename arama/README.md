# Arama — Express sunucusu

YouTube videolarını arayıp izlemeye yarayan site ve onu yayınlayan Express sunucusu.

Kullanıcı verileri (izleme geçmişi, kaldığın yer, ayarlar) **yalnızca tarayıcıda**
(`localStorage`) tutulur; sunucuya gönderilmez ve sunucuda saklanmaz. Sunucu sadece siteyi
yayınlar ve YouTube aramasını yapar. Tarayıcı verilerini silersen geçmiş de silinir;
her tarayıcı/cihazın geçmişi ayrıdır.

## YouTube API anahtarı

Metin araması resmi **YouTube Data API v3** ile yapılır ve bir API anahtarı gerektirir:

1. https://console.cloud.google.com adresinde bir proje oluştur.
2. "APIs & Services → Library" bölümünden **YouTube Data API v3**'ü etkinleştir.
3. "Credentials → Create credentials → API key" ile anahtar oluştur
   (istersen "API restrictions" kısmından yalnızca YouTube Data API v3 ile sınırla).
4. Sunucuyu anahtarla başlat:

```bash
YOUTUBE_API_KEY=anahtarın npm start
```

Anahtar yoksa site yine çalışır: arama kutusuna bir YouTube linki (ya da 11 karakterlik
video kimliği) yapıştırarak video açabilirsin; metin araması açıklayıcı bir hata gösterir.

Kota: ücretsiz kota günde 10.000 birimdir; bir arama ~101 birim harcar (≈ günde 100 arama).
Aynı arama 10 dakika boyunca sunucuda önbellekten döner.

## Çalıştırma

```bash
cd arama
npm install
npm start          # http://localhost:3000
npm run dev        # dosya değişince otomatik yeniden başlar
```

Ortam değişkenleri:

| Değişken       | Varsayılan          | Açıklama                    |
| -------------- | ------------------- | --------------------------- |
| `PORT`         | `3000`              | Dinlenecek port             |
| `HOST`         | `0.0.0.0`           | Dinlenecek adres            |
| `YOUTUBE_API_KEY` | —                | Metin araması için YouTube Data API v3 anahtarı |
| `YOUTUBE_BOLGE` | `TR`               | Arama bölgesi (regionCode)  |
| `YOUTUBE_DIL`   | `tr`               | Arama dili                  |

## Yapı

- `server.js` — Express uygulaması, arama API'si, statik dosya sunumu
- `yt.js` — YouTube araması (Data API v3, linkler için anahtarsız oEmbed)
- `public/index.html` — site
- `public/app.js` — sayfanın betiği (arama, oynatıcı, localStorage'daki geçmiş ve ayarlar)

## Sitenin özellikleri

- Arama: Enter ile arar, "Daha fazla göster" ile sonraki sayfayı getirir; son arama adres
  çubuğunda (`?q=`) ve tarayıcıda hatırlanır.
- Oynatma: YouTube IFrame oynatıcısı (`youtube-nocookie.com`). İzlerken konum ve ilerleme
  yüzdesi tarayıcıya kaydedilir; "Kaldığım yerden devam et" açıksa video oradan başlar.
- Sayfa açılınca son izlenen video kaldığı yerden hazırlanır (otomatik başlamaz).
- Aynı sitenin açık diğer sekmeleri geçmiş ve ayar değişikliklerini anında görür.
- "Otomatik oynat" kapalıyken seçilen video yalnızca yüklenir, oynat tuşuna basman gerekir.

## API

### Arama

| Yöntem | Yol                         | Açıklama                                     |
| ------ | --------------------------- | -------------------------------------------- |
| `GET`  | `/api/ara?q=...&sayfa=...`  | Video arar; `q` bir YouTube linkiyse o videoyu döndürür |

Yanıt: `{ "sonuclar": [{ id, baslik, kanal, sure, kucukResim, izlenmeSayisi, yayinTarihi, canli }], "sonrakiSayfa": "..." | null }`.
Anahtar yoksa `503` (`kod: "ANAHTAR_YOK"`), kota dolduysa `429` (`kod: "KOTA_DOLDU"`).

### Diğer

- `GET /api/saglik` — sağlık kontrolü

Hatalar `{ "hata": "..." }` biçiminde ve uygun HTTP koduyla döner.

## Tarayıcıda saklanan veriler

| `localStorage` anahtarı | İçerik                                                        |
| ----------------------- | ------------------------------------------------------------- |
| `arama.gecmis`          | İzleme geçmişi (en fazla 200 video; konum ve ilerleme dahil) |
| `arama.ayarlar`         | Tema, oynatma ve diğer ayarlar                                |
| `arama.sonArama`        | Son yapılan arama                                             |
