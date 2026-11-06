# Arama — Express sunucusu

Arama sitesini yayınlayan ve geçmiş/ayarlar için JSON API sunan Express sunucusu.

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

Veri dosyası yoksa ilk açılışta örnek geçmişle oluşturulur.

## Yapı

- `server.js` — Express uygulaması, API rotaları, statik dosya sunumu
- `depo.js` — geçmiş ve ayarları JSON dosyasında saklayan depo (atomik yazma)
- `public/index.html` — site
- `public/app.js` — sayfanın API ile konuşan betiği

## API

### Geçmiş

| Yöntem   | Yol                | Açıklama                                                   |
| -------- | ------------------ | ---------------------------------------------------------- |
| `GET`    | `/api/gecmis`      | En yeniden eskiye izleme geçmişi                           |
| `POST`   | `/api/gecmis`      | Kayıt ekler/günceller ve en üste taşır                     |
| `DELETE` | `/api/gecmis`      | Tüm geçmişi siler                                          |
| `DELETE` | `/api/gecmis/:id`  | Tek kaydı siler                                            |

`POST` gövdesi:

```json
{ "id": "v12", "baslik": "Video başlığı 12", "kanal": "Kanal adı", "sure": "2:35", "ilerleme": 40 }
```

`id` ve `baslik` zorunlu, `ilerleme` 0–100 arası. "Geçmişi kaydet" ayarı kapalıyken `409` döner.

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
