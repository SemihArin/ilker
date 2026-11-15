#!/usr/bin/env python3
"""
Konum Bildirici
---------------
Telefondaki OwnTracks uygulamasından konumunu alır; yola çıktığında ve bir
yere vardığında Telegram üzerinden sevgiline otomatik mesaj atar.

Sadece Python standart kütüphanesi kullanır, ek paket gerekmez.

Ayarlar ortam değişkenleriyle yapılır (bkz. README.md / .env.example).
"""

import base64
import json
import math
import os
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ---------------------------------------------------------------- ayarlar ---

BOT_TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "")
CHAT_ID = os.environ.get("TELEGRAM_CHAT_ID", "")
HTTP_USER = os.environ.get("HTTP_USER", "")
HTTP_PASS = os.environ.get("HTTP_PASS", "")
PORT = int(os.environ.get("PORT", "8080"))
STATE_FILE = os.environ.get("STATE_FILE", "durum.json")

# Bu kadar metre uzaklaşınca "yola çıktı" sayılır.
MOVE_THRESHOLD_M = float(os.environ.get("MOVE_THRESHOLD_M", "700"))
# Bu kadar dakika aynı yerde kalınca "vardı" sayılır.
ARRIVE_MINUTES = float(os.environ.get("ARRIVE_MINUTES", "10"))
# Doğruluğu bundan kötü (metre) olan GPS noktaları yok sayılır.
MAX_ACCURACY_M = float(os.environ.get("MAX_ACCURACY_M", "150"))
# Mesajlar arasında en az bu kadar dakika olsun (trafik ışığında spam olmasın).
COOLDOWN_MINUTES = float(os.environ.get("COOLDOWN_MINUTES", "5"))
# Bilinen yerler: [{"name":"Ev","lat":41.0,"lon":29.0,"radius":200}, ...]
PLACES = json.loads(os.environ.get("PLACES", "[]"))
# Takma ad, mesajlarda kullanılır.
NICK = os.environ.get("NICK", "Aşkın")
# 1 ise Telegram'a gerçekten göndermez, sadece ekrana yazar (test için).
DRY_RUN = os.environ.get("DRY_RUN", "0") == "1"

# --------------------------------------------------------------- yardımcı ---


def haversine_m(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def place_name(lat, lon):
    """Konum bilinen bir yerin içindeyse adını döndürür."""
    best = None
    for p in PLACES:
        d = haversine_m(lat, lon, p["lat"], p["lon"])
        if d <= p.get("radius", 200) and (best is None or d < best[0]):
            best = (d, p["name"])
    return best[1] if best else None


def maps_link(lat, lon):
    return f"https://maps.google.com/?q={lat:.6f},{lon:.6f}"


def send_telegram(text, lat=None, lon=None):
    print(f"[telegram] {text}", flush=True)
    if DRY_RUN:
        return
    if not BOT_TOKEN or not CHAT_ID:
        print("[uyarı] TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID ayarlı değil", flush=True)
        return
    api = f"https://api.telegram.org/bot{BOT_TOKEN}"
    try:
        data = urllib.parse.urlencode({"chat_id": CHAT_ID, "text": text}).encode()
        urllib.request.urlopen(f"{api}/sendMessage", data=data, timeout=15).read()
        if lat is not None:
            data = urllib.parse.urlencode(
                {"chat_id": CHAT_ID, "latitude": lat, "longitude": lon}
            ).encode()
            urllib.request.urlopen(f"{api}/sendLocation", data=data, timeout=15).read()
    except Exception as e:  # ağ hatası sunucuyu düşürmesin
        print(f"[hata] Telegram gönderilemedi: {e}", flush=True)


# ------------------------------------------------------------------ durum ---


class Tracker:
    """
    Basit durum makinesi:
      stationary --(anchor'dan MOVE_THRESHOLD_M uzaklaştı)--> moving  => "yola çıktım"
      moving --(ARRIVE_MINUTES boyunca yerinde kaldı)--> stationary   => "vardım"
    """

    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        self.s = {
            "state": "stationary",
            "anchor": None,  # [lat, lon, since_ts]
            "last": None,  # [lat, lon, ts]
            "last_msg_ts": 0,
            "from_place": None,
        }
        try:
            with open(path) as f:
                self.s.update(json.load(f))
        except (FileNotFoundError, json.JSONDecodeError):
            pass

    def _save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.s, f)
        os.replace(tmp, self.path)

    def _can_msg(self, now):
        return now - self.s["last_msg_ts"] >= COOLDOWN_MINUTES * 60

    def on_location(self, lat, lon, ts, acc=None):
        if acc is not None and acc > MAX_ACCURACY_M:
            return
        with self.lock:
            self.s["last"] = [lat, lon, ts]
            anchor = self.s["anchor"]
            if anchor is None:
                self.s["anchor"] = [lat, lon, ts]
                self._save()
                return
            d = haversine_m(anchor[0], anchor[1], lat, lon)
            if d > MOVE_THRESHOLD_M:
                if self.s["state"] == "stationary":
                    origin = place_name(anchor[0], anchor[1])
                    self.s["from_place"] = origin
                    self.s["state"] = "moving"
                    if self._can_msg(ts):
                        where = f" ({origin})" if origin else ""
                        send_telegram(f"🚗 {NICK}: Yola çıktım{where}. Varınca haber veririm 💌")
                        self.s["last_msg_ts"] = ts
                # hareket ederken çapa sürekli yeni noktaya taşınır
                self.s["anchor"] = [lat, lon, ts]
            self._save()
        self.check_arrival(ts)

    def check_arrival(self, now=None):
        now = now or time.time()
        with self.lock:
            if self.s["state"] != "moving" or self.s["anchor"] is None:
                return
            lat, lon, since = self.s["anchor"]
            if now - since < ARRIVE_MINUTES * 60:
                return
            self.s["state"] = "stationary"
            # Gelen son nokta varış noktasıdır
            if self.s["last"]:
                lat, lon = self.s["last"][0], self.s["last"][1]
            dest = place_name(lat, lon)
            if dest == self.s.get("from_place") and dest is not None:
                text = f"🏠 {NICK}: Geri döndüm → {dest}"
            elif dest:
                text = f"📍 {NICK}: Vardım → {dest} ❤️"
            else:
                text = f"📍 {NICK}: Vardım ❤️\n{maps_link(lat, lon)}"
            send_telegram(text, None if dest else lat, None if dest else lon)
            self.s["last_msg_ts"] = now
            self._save()


tracker = Tracker(STATE_FILE)

# ------------------------------------------------------------------- http ---


class Handler(BaseHTTPRequestHandler):
    def _auth_ok(self):
        if not HTTP_USER:
            return True
        h = self.headers.get("Authorization", "")
        if not h.startswith("Basic "):
            return False
        try:
            u, _, p = base64.b64decode(h[6:]).decode().partition(":")
        except Exception:
            return False
        return u == HTTP_USER and p == HTTP_PASS

    def _reply(self, code, body=b"[]", ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/saglik":
            self._reply(200, b"ok", "text/plain")
        else:
            self._reply(404, b"", "text/plain")

    def do_POST(self):
        if not self._auth_ok():
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="konum"')
            self.end_headers()
            return
        try:
            n = int(self.headers.get("Content-Length", "0"))
            msg = json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            self._reply(400, b"[]")
            return
        if msg.get("_type") == "location" and "lat" in msg and "lon" in msg:
            tracker.on_location(
                float(msg["lat"]),
                float(msg["lon"]),
                float(msg.get("tst", time.time())),
                msg.get("acc"),
            )
        # OwnTracks HTTP modu yanıt olarak JSON dizisi bekler
        self._reply(200, b"[]")

    def log_message(self, fmt, *args):
        pass  # sessiz


def arrival_loop():
    # Telefon durunca konum göndermeyi bırakabilir; varışı zamanlayıcıyla yakala.
    while True:
        time.sleep(60)
        try:
            tracker.check_arrival()
        except Exception as e:
            print(f"[hata] {e}", flush=True)


def main():
    threading.Thread(target=arrival_loop, daemon=True).start()
    print(f"Konum bildirici {PORT} portunda dinliyor (DRY_RUN={DRY_RUN})", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
