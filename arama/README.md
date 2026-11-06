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

- **Arama:** Enter ile arar, "Daha fazla göster" ile sonraki sayfayı getirir. Arama kutusuna
  tıklayınca son aramalar önerilir (ok tuşlarıyla seçilebilir, tek tek ya da toplu silinebilir).
  YouTube linki yapıştırılırsa video doğrudan açılır.
- **Adresler:** `/`, `/?q=arama`, `/kaydedilenler`, `/gecmis`, `/ayarlar`. Tarayıcının geri/ileri tuşları ve
  sayfa yenileme çalışır; aramalar paylaşılabilir.
- **Oynatma:** YouTube IFrame oynatıcısı (`youtube-nocookie.com`), yalnızca ilk video
  seçildiğinde yüklenir. Altında "şimdi oynuyor" bilgisi ile YouTube'da aç, linki kopyala ve
  kapat düğmeleri var. İzlerken konum ve ilerleme tarayıcıya kaydedilir; "Kaldığım yerden
  devam et" açıksa video oradan başlar ("Baştan başlat" ile sıfırlanabilir).
- **Kaydedilenler (daha sonra izle):** Her videonun yanındaki kaydet simgesiyle ya da
  oynatıcının altındaki düğmeyle eklenir; ayrı sayfada listelenir, "Tümünü oynat" ile sırayla
  izlenir.
- **Sıradaki:** Video hangi listeden açıldıysa (arama sonuçları, kaydedilenler, geçmiş)
  oynatıcının altında sıradaki video gösterilir. Video bitince 5 sn geri sayımla sıradaki başlar
  (bildirimden iptal edilebilir; Ayarlar → "Sıradakini otomatik oynat" ile kapatılabilir).
- **Paylaşma:** Telefonlarda sistemin paylaş menüsü açılır, diğer cihazlarda link kopyalanır.
- **Yedekle / taşı:** Ayarlar → Veriler'den geçmiş, kaydedilenler, son aramalar ve ayarlar JSON
  dosyası olarak indirilebilir ve başka bir tarayıcıya yüklenebilir (yükleme geri alınabilir).
- **İzlemeye devam et:** Ana sayfada (arama yokken) son izlenenler, kalan süreyle birlikte.
- **Geçmiş:** Gün gruplarına ayrılmış liste, geçmişte arama, tek tek silme ve tümünü
  temizleme; ikisi de bildirimdeki "Geri al" ile geri alınabilir.
- **Klavye:** `/` arama kutusu · `K` oynat/duraklat · `J` / `L` 10 sn geri/ileri ·
  `N` sıradaki video · `Esc` öneri listesini kapat ·
  sonuçlar ve kartlar Tab + Enter ile açılır · geçmişte ve kaydedilenlerde seçili satır
  `Delete` ile kaldırılır.
- Açık, koyu ve sistem teması; telefon, tablet ve masaüstü düzeni.
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
| `arama.kaydedilenler`   | Daha sonra izle listesi (en fazla 500 video)                  |
| `arama.sonAramalar`     | Son 10 arama (arama kutusu önerileri)                         |
