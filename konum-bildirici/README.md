# 💌 Konum Bildirici

Telefonun konumunu 7/24 takip eder; **yola çıktığında** ve **bir yere vardığında** manitana Telegram'dan otomatik mesaj atar.

```
🚗 Aşkın: Yola çıktım (Ev). Varınca haber veririm 💌
📍 Aşkın: Vardım → İş ❤️
📍 Aşkın: Vardım ❤️  (+ harita konumu)
```

## Nasıl çalışıyor?

```
Telefon (OwnTracks) ──konum──▶ Sunucu (bildirici.py) ──mesaj──▶ Telegram Botu ──▶ Manita
```

- Bulunduğun noktadan **700 m** uzaklaşırsan → "yola çıktım"
- Bir yerde **10 dk** durursan → "vardım"
- Bilinen yerlerdeysen (Ev, İş, Okul...) isimle yazar, değilse harita konumu yollar.
- Trafik ışığında / GPS sapmasında spam yapmasın diye eşikler ve bekleme süresi var.

> **Neden Instagram değil?** Instagram'ın kişisel hesaplardan otomatik DM atmaya izin veren bir API'si yok; bot gibi davranan hesaplar kapatılabiliyor. Telegram botları ise resmî ve ücretsiz.

---

## Kurulum (≈15 dk)

### 1) Telegram botunu oluştur
1. Telegram'da **@BotFather**'a yaz → `/newbot` → bir isim ver.
2. Verdiği **token**'ı kaydet (`123456:AAH...`).
3. **Manitan** bota girip **Başlat (/start)** demeli (bot, kendisine yazmamış birine mesaj atamaz).
4. Manitanın chat id'sini bul — tarayıcıda aç:
   `https://api.telegram.org/bot<TOKEN>/getUpdates`
   Çıkan JSON'da `"chat":{"id": 987654321, ...}` → bu sayı `TELEGRAM_CHAT_ID`.

### 2) Sunucuyu çalıştır
7/24 açık bir yerde çalışmalı (telefon her an konum gönderebilsin diye). Seçenekler:
- Ücretsiz/ucuz: **Oracle Cloud Free**, **Fly.io**, **Railway**, **Render**, evdeki **Raspberry Pi** vb.
- HTTPS önerilir (Railway/Render/Fly otomatik verir).

```bash
cp .env.example .env      # içini doldur
docker build -t konum-bildirici .
docker run -d --restart=always --env-file .env -p 8080:8080 -v konum:/data konum-bildirici
```

Docker yoksa: `set -a; source .env; set +a; python3 bildirici.py` (Python 3.8+, ek paket gerekmez).

Kontrol: `https://SUNUCU_ADRESIN/saglik` → `ok` görmelisin.

### 3) Telefona OwnTracks kur (iOS & Android, ücretsiz)
1. **OwnTracks** uygulamasını indir.
2. Ayarlar → **Mode: HTTP**
3. **URL:** `https://SUNUCU_ADRESIN/`
4. **Authentication** aç → kullanıcı/şifre: `.env`'deki `HTTP_USER` / `HTTP_PASS`
5. Konum iznini **"Her zaman" (Always)** ver, pil optimizasyonunu OwnTracks için kapat (Android).
6. İzleme modu: **Significant** (pil dostu) ya da daha hassas takip için **Move**.

### 4) Bilinen yerleri ekle
Google Haritalar'da yere uzun bas → koordinatları kopyala → `.env` içindeki `PLACES`'e ekle:
```
PLACES=[{"name":"Ev","lat":41.0082,"lon":28.9784,"radius":200}]
```

## Test
Sunucuyu `DRY_RUN=1` ile çalıştırırsan mesajları Telegram'a göndermez, sadece ekrana yazar.

## Ayarlar
| Değişken | Varsayılan | Açıklama |
|---|---|---|
| `MOVE_THRESHOLD_M` | 700 | Kaç metre uzaklaşınca "yola çıktı" |
| `ARRIVE_MINUTES` | 10 | Kaç dakika durunca "vardı" |
| `COOLDOWN_MINUTES` | 5 | İki mesaj arası en az süre |
| `MAX_ACCURACY_M` | 150 | Bundan kötü GPS noktaları yok sayılır |
| `NICK` | Aşkın | Mesajdaki isim |

---

## Sunucusuz alternatif (daha basit, daha az akıllı)
Sadece belli yerler için (ör. "evden çıkınca") yeterliyse sunucu gerekmez:
- **iPhone – Kestirmeler:** Otomasyon → *Ayrıldığımda* (konum seç) → *URL İçeriğini Al*:
  `https://api.telegram.org/bot<TOKEN>/sendMessage?chat_id=<CHAT_ID>&text=Evden çıktım 🚗`
  "Çalıştırmadan önce sor"u kapat.
- **Android – MacroDroid:** Tetikleyici *Coğrafi Çit (çıkış/giriş)* → Eylem *HTTP İsteği* (aynı URL).
