# Arama — Express sunucusu

YouTube videolarını arayıp izlemeye yarayan site ve onu yayınlayan Express sunucusu.
Geçmiş (kaldığın yer dahil) ve ayarlar sunucuda saklanır.

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
| `VERI_DOSYASI` | `./data/veri.json`  | Geçmiş ve ayarların dosyası |
| `YOUTUBE_API_KEY` | —                | Metin araması için YouTube Data API v3 anahtarı |
| `YOUTUBE_BOLGE` | `TR`               | Arama bölgesi (regionCode)  |
| `YOUTUBE_DIL`   | `tr`               | Arama dili                  |

Veri dosyası yoksa ilk açılışta boş olarak oluşturulur.

## Yapı

- `server.js` — Express uygulaması, API rotaları, statik dosya sunumu
- `yt.js` — YouTube araması (Data API v3, linkler için anahtarsız oEmbed)
- `depo.js` — geçmiş ve ayarları JSON dosyasında saklayan depo (atomik yazma)
- `public/index.html` — site
- `public/app.js` — sayfanın API ile konuşan betiği

## Sitenin özellikleri

- Arama: Enter ile arar, "Daha fazla göster" ile sonraki sayfayı getirir; son arama adres
  çubuğunda (`?q=`) ve tarayıcıda hatırlanır.
- Oynatma: YouTube IFrame oynatıcısı (`youtube-nocookie.com`). İzlerken konum ve ilerleme
  yüzdesi sunucuya kaydedilir; "Kaldığım yerden devam et" açıksa video oradan başlar.
- Sayfa açılınca son izlenen video kaldığı yerden hazırlanır (otomatik başlamaz).
- "Otomatik oynat" kapalıyken seçilen video yalnızca yüklenir, oynat tuşuna basman gerekir.

## API

### Arama

| Yöntem | Yol                         | Açıklama                                     |
| ------ | --------------------------- | -------------------------------------------- |
| `GET`  | `/api/ara?q=...&sayfa=...`  | Video arar; `q` bir YouTube linkiyse o videoyu döndürür |

Yanıt: `{ "sonuclar": [{ id, baslik, kanal, sure, kucukResim, izlenmeSayisi, yayinTarihi, canli }], "sonrakiSayfa": "..." | null }`.
Anahtar yoksa `503` (`kod: "ANAHTAR_YOK"`), kota dolduysa `429` (`kod: "KOTA_DOLDU"`).

### Geçmiş

| Yöntem   | Yol                | Açıklama                                                   |
| -------- | ------------------ | ---------------------------------------------------------- |
| `GET`    | `/api/gecmis`      | En yeniden eskiye izleme geçmişi                           |
| `POST`   | `/api/gecmis`      | Kayıt ekler/günceller ve en üste taşır                     |
| `DELETE` | `/api/gecmis`      | Tüm geçmişi siler                                          |
| `PATCH`  | `/api/gecmis/:id`  | İzleme ilerlemesini günceller (`ilerleme`, `konum`, `sure`); sırayı değiştirmez |
| `DELETE` | `/api/gecmis/:id`  | Tek kaydı siler                                            |

`POST` gövdesi:

```json
{ "id": "jNQXAC9IVRw", "baslik": "Me at the zoo", "kanal": "jawed", "sure": "0:19",
  "kucukResim": "https://i.ytimg.com/vi/jNQXAC9IVRw/mqdefault.jpg", "ilerleme": 40, "konum": 8 }
```

`id` (11 karakterlik YouTube video kimliği) ve `baslik` zorunlu; `ilerleme` 0–100 arası,
`konum` saniye cinsinden. "Geçmişi kaydet" ayarı kapalıyken `409` döner.

### Ayarlar

| Yöntem  | Yol             | Açıklama                              |
| ------- | --------------- | ------------------------------------- |
| `GET`   | `/api/ayarlar`  | Tüm ayarlar                           |
| `PATCH` | `/api/ayarlar`  | Yalnızca gönderilen alanları günceller |

Alanlar: `tema` (`sistem` / `acik` / `koyu`), `otomatikOynat`, `kaldigimYerdenDevam`,
`gecmisiKaydet`, `davetleriKabulEt` (true/false), `kullaniciAdi` (en fazla 32 karakter).

### Diğer

- `GET /api/saglik` — sağlık kontrolü

Hatalar `{ "hata": "..." }` biçiminde ve uygun HTTP koduyla döner.
