# Arama — Node.js sunucusu

Bağımlılık gerektirmeyen (yalnızca Node.js çekirdek modülleri) basit bir sunucu.

## Çalıştırma

```bash
cd arama
npm start          # ya da: node server.js
npm run dev        # dosya değişince otomatik yeniden başlar
```

Tarayıcıda: http://localhost:3000

`PORT` ve `HOST` ortam değişkenleriyle değiştirilebilir:

```bash
PORT=8080 npm start
```

## Yapı

- `server.js` — sunucu
- `public/` — statik dosyalar (`index.html` site)

## Özellikler

- `public/` klasöründen statik dosya sunumu (doğru MIME türleri)
- gzip sıkıştırma, ETag / 304 önbellek desteği
- Dizin dışına çıkma (path traversal) koruması
- Uzantısız bilinmeyen yollar `index.html`'e yönlenir
- Sağlık kontrolü: `GET /api/saglik`
- Ctrl+C / SIGTERM ile düzgün kapanma
