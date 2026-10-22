#!/usr/bin/env python3
"""
Telefon-PC ajanı (prototip, sürüm 0.3)

1) Hızlı kilit: telefonun BLE reklam yayınını dinler; kilit mesafesi aşılınca saniyeler içinde kilitler.
2) Oturum açma desteği: bilgisayarın kilit durumunu telefona bildirir (yaklaşma / kilitli / açıldı).
   PIN'i telefon yazar; ajan sadece durumu iletir.
3) Otomatik giriş: tarayıcı eklentisinden (127.0.0.1) gelen "şu siteye giriş yapılacak"
   isteğini HMAC ile imzalayıp telefona BLE (GATT) üzerinden iletir.

Kurulum:   pip install bleak
Çalıştır:  python agent.py
Kalibrasyon: python agent.py --calibrate
"""

import asyncio
import collections
import hashlib
import hmac
import json
import os
import secrets
import socket
import statistics
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SERVICE_UUID = "7b3f6a10-5c1e-4d2a-9f60-2b7e4a1c9d01"
MOVING_UUID = "7b3f6a12-5c1e-4d2a-9f60-2b7e4a1c9d01"   # telefon hareket ederken yayınlanır
CHAR_UUID = "7b3f6a11-5c1e-4d2a-9f60-2b7e4a1c9d01"

CONFIG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "agent_config.json")
DEFAULTS = {
    "phone_key_hex": "",       # Telefon uygulamasında "PC eşleştirme anahtarını göster" ile alınır
    "ext_token": "",           # Tarayıcı eklentisinin config.js dosyasındaki TOKEN ile aynı olmalı
    "http_port": 8765,
    "lock_enabled": True,
    "unlock_support": True,    # Kilit durumunu telefona bildir (telefondan PIN ile açma için)
    "lock_rssi": -80,          # dBm. Bundan zayıfsa "kilit mesafesinin ötesinde" sayılır
    "near_rssi": -72,          # dBm. Kilitliyken bundan güçlüyse "yaklaştı" sayılır
    "sensitivity": "fast",     # fast | normal | slow
    "debug": False,            # True: RSSI ve kilit durumunu yazdırır
}

# Hassasiyet profilleri: pencere (sn), "uzak" örnek oranı, en az örnek, yayın kaybı (sn)
PROFILES = {
    "fast":   {"window": 1.5, "fraction": 0.6, "min_samples": 3, "lost": 5.0},
    "normal": {"window": 3.0, "fraction": 0.7, "min_samples": 4, "lost": 10.0},
    "slow":   {"window": 5.0, "fraction": 0.8, "min_samples": 5, "lost": 15.0},
}
# Telefon hareket halindeyse (hareket algısı açıkken) daha kısa pencere
MOVING_WINDOW = 1.0
MOVING_MIN_SAMPLES = 2

STAGES = {"user", "pass", "both"}
HEARTBEAT_SECONDS = 8.0


def load_config():
    cfg = dict(DEFAULTS)
    legacy = None
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            stored = json.load(f)
        cfg.update(stored)
        if "lock_rssi" not in stored and "rssi_threshold" in stored:
            legacy = stored["rssi_threshold"]
    if legacy is not None:
        cfg["lock_rssi"] = legacy
        cfg["near_rssi"] = legacy + 8
    changed = False
    if not cfg["ext_token"]:
        cfg["ext_token"] = secrets.token_hex(16)
        changed = True
    if not os.path.exists(CONFIG_PATH) or changed:
        save_config(cfg)
    return cfg


def save_config(cfg):
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)


# ---------- Yakınlık hesabı (saf mantık, test edilebilir) ----------

class Proximity:
    """RSSI örneklerinden 'uzak', 'yakın', 'kayıp' kararlarını üretir."""

    def __init__(self, lock_rssi, near_rssi, profile):
        self.lock_rssi = lock_rssi
        self.near_rssi = near_rssi
        self.p = profile
        self.samples = collections.deque()   # (t, rssi)
        self.last_seen = None
        self.moving_until = 0.0
        self.run_start = None                # eşiğin altına ilk düşme anı (gecikme ölçümü)

    def add(self, t, rssi, moving=False):
        self.samples.append((t, rssi))
        self.last_seen = t
        if moving:
            self.moving_until = t + 4.0
        while self.samples and t - self.samples[0][0] > 6.0:
            self.samples.popleft()
        if rssi < self.lock_rssi:
            if self.run_start is None:
                self.run_start = t
        else:
            self.run_start = None

    def _recent(self, now, seconds):
        return [r for (t, r) in self.samples if now - t <= seconds]

    def is_moving(self, now):
        return now < self.moving_until

    def seen_recently(self, now, seconds):
        return self.last_seen is not None and now - self.last_seen <= seconds

    def lost(self, now):
        return self.last_seen is not None and now - self.last_seen > self.p["lost"]

    def is_far(self, now):
        """Son pencerede örneklerin yeterli oranı kilit eşiğinin altındaysa True."""
        if self.is_moving(now):
            window, need = MOVING_WINDOW, MOVING_MIN_SAMPLES
        else:
            window, need = self.p["window"], self.p["min_samples"]
        recent = self._recent(now, window)
        if len(recent) < need:
            return False
        below = sum(1 for r in recent if r < self.lock_rssi)
        return below / len(recent) >= self.p["fraction"]

    def is_near(self, now):
        """Kilitliyken 'yaklaştı': son 2 sn'nin ortancası near_rssi'den güçlü."""
        recent = self._recent(now, 2.0)
        if len(recent) < 2:
            return False
        return statistics.median(recent) >= self.near_rssi

    def median(self, now, seconds=2.0):
        recent = self._recent(now, seconds)
        return statistics.median(recent) if recent else None

    def reset(self):
        self.samples.clear()
        self.run_start = None


# ---------- Bilgisayar işlemleri ----------

def lock_screen():
    if sys.platform.startswith("win"):
        import ctypes

        ctypes.windll.user32.LockWorkStation()
    elif sys.platform == "darwin":
        os.system("pmset displaysleepnow")
    else:
        os.system("loginctl lock-session")


def is_locked_raw():
    """Windows: giriş masaüstü kullanıcıya aitse açık, değilse (kilit ekranı) kilitli."""
    if not sys.platform.startswith("win"):
        return False
    import ctypes

    user32 = ctypes.windll.user32
    user32.OpenInputDesktop.restype = ctypes.c_void_p
    user32.SwitchDesktop.argtypes = [ctypes.c_void_p]
    user32.CloseDesktop.argtypes = [ctypes.c_void_p]
    h = user32.OpenInputDesktop(0, False, 0x0100)  # DESKTOP_SWITCHDESKTOP
    if not h:
        return True
    try:
        return user32.SwitchDesktop(h) == 0
    finally:
        user32.CloseDesktop(h)


# ---------- Ajan ----------

cfg = None
loop = None
send_lock = None
prox = None
device = None
last_login_sent = {}
last_ts = 0
PC_NAME = (socket.gethostname() or "PC").replace("|", "_")[:60]


def on_detect(dev, adv):
    global device
    uuids = [u.lower() for u in (adv.service_uuids or [])]
    if SERVICE_UUID not in uuids and MOVING_UUID not in uuids:
        return
    device = dev
    if adv.rssi is not None and prox is not None:
        prox.add(time.monotonic(), adv.rssi, moving=(MOVING_UUID in uuids))


def next_ts():
    global last_ts
    ts = max(int(time.time() * 1000), last_ts + 1)
    last_ts = ts
    return ts


async def send_command(host, stage, attempts=3):
    """İmzalı komutu telefona GATT ile yazar. Başarılıysa True."""
    key_hex = cfg["phone_key_hex"]
    if not key_hex:
        return False
    try:
        key = bytes.fromhex(key_hex)
    except ValueError:
        print("phone_key_hex geçersiz (hex olmalı)")
        return False
    dev = device
    if dev is None:
        return False

    from bleak import BleakClient

    async with send_lock:
        for attempt in range(1, attempts + 1):
            ts = next_ts()
            msg = f"{ts}|{host}|{stage}"
            mac = hmac.new(key, msg.encode(), hashlib.sha256).hexdigest()[:32]
            payload = f"{msg}|{mac}".encode()
            try:
                async with BleakClient(dev, timeout=8.0) as client:
                    await client.write_gatt_char(CHAR_UUID, payload, response=True)
                return True
            except Exception as e:  # noqa: BLE001
                if cfg["debug"] or stage in STAGES:
                    print(f"Gönderim denemesi {attempt} ({stage}) başarısız: {e}")
                await asyncio.sleep(0.5)
    return False


async def send_login(host, stage):
    now = time.monotonic()
    k = (host, stage)
    if now - last_login_sent.get(k, 0) < 5:
        return
    last_login_sent[k] = now
    if not cfg["phone_key_hex"]:
        print("phone_key_hex ayarlı değil, giriş isteği gönderilemiyor")
        return
    if not prox.seen_recently(now, 15):
        print("Telefon görünmüyor, istek gönderilemedi")
        return
    ok = await send_command(host, stage)
    print(f"Telefona gönderildi: {host} ({stage})" if ok else "Giriş isteği gönderilemedi")


class Monitor:
    """Kilit kararı + kilit durumu bildirimleri."""

    def __init__(self):
        self.armed = False             # telefon yakınken devreye girer
        self.locked = False            # debounce edilmiş kilit durumu
        self._raw_hits = 0
        self.approach_armed = False    # uzaklaştıktan sonra ilk yaklaşmada bildirim hakkı
        self.last_heartbeat = 0.0
        self.last_debug = 0.0

    def poll_lock_state(self):
        raw = is_locked_raw()
        if raw:
            self._raw_hits += 1
        else:
            self._raw_hits = 0
        # UAC gibi kısa güvenli masaüstü geçişleri kilit sayılmasın: ~1 sn doğrulama
        if not self.locked and self._raw_hits >= 2:
            return "locked"
        if self.locked and self._raw_hits == 0:
            return "unlocked"
        return None

    async def step(self, now, tick_count):
        # --- kilit durumu (1 sn'de bir) ---
        if tick_count % 4 == 0:
            ev = self.poll_lock_state()
            if ev == "locked":
                self.locked = True
                # Kilit uzaklıktan olduysa veya telefon şu an uzaktaysa: ilk yaklaşmada sor.
                far_now = prox.last_seen is None or prox.is_far(now) or prox.lost(now)
                self.approach_armed = far_now
                self.armed = False
                self.last_heartbeat = 0.0
                print("Bilgisayar kilitlendi")
            elif ev == "unlocked":
                self.locked = False
                self.approach_armed = False
                self.armed = False
                print("Bilgisayarın kilidi açıldı")
                if cfg["unlock_support"] and prox.seen_recently(now, 10):
                    asyncio.ensure_future(send_command(PC_NAME, "unlocked", attempts=2))

        if cfg["debug"] and now - self.last_debug >= 2:
            self.last_debug = now
            med = prox.median(now, 2.0)
            print(f"RSSI ort: {med if med is None else round(med)} dBm | "
                  f"{'kilitli' if self.locked else 'açık'} | "
                  f"{'ARMED' if self.armed else 'disarmed'}"
                  f"{' | hareket' if prox.is_moving(now) else ''}")

        # --- kilitliyken: yaklaşma + kalp atışı ---
        if self.locked:
            if cfg["unlock_support"]:
                far = prox.is_far(now) or prox.lost(now)
                if far:
                    self.approach_armed = True
                if self.approach_armed and not far and prox.is_near(now):
                    self.approach_armed = False
                    self.last_heartbeat = now
                    print("Telefon yaklaştı, açma isteği gönderiliyor")
                    asyncio.ensure_future(send_command(PC_NAME, "approach"))
                elif (not far and prox.seen_recently(now, 3)
                      and now - self.last_heartbeat >= HEARTBEAT_SECONDS):
                    self.last_heartbeat = now
                    asyncio.ensure_future(send_command(PC_NAME, "locked", attempts=2))
            return

        # --- açıkken: uzaklaşınca kilitle ---
        if not cfg["lock_enabled"]:
            return

        if not self.armed:
            # Yanlışlıkla hemen kilitlenmemek için: önce telefon yakında görülmeli
            if prox.seen_recently(now, 3) and not prox.is_far(now):
                med = prox.median(now, 2.0)
                if med is not None and med >= cfg["lock_rssi"] + 3:
                    self.armed = True
                    print("Kilit koruması aktif")
            return

        if prox.is_far(now) or prox.lost(now):
            latency = None
            if prox.run_start is not None:
                latency = now - prox.run_start
            elif prox.last_seen is not None:
                latency = now - prox.last_seen
            why = "yayın kesildi" if prox.lost(now) else "eşik aşıldı"
            lock_screen()
            self.armed = False
            msg = f"Telefon uzakta ({why}), oturum kilitlendi"
            if latency is not None:
                msg += f" — eşiği aşmaktan kilide: {latency:.1f} sn"
            print(msg)
            prox.reset()


async def monitor_loop():
    mon = Monitor()
    tick = 0
    while True:
        await asyncio.sleep(0.25)
        tick += 1
        now = time.monotonic()
        if prox.last_seen is None:
            if tick % 40 == 0:
                print("Telefon henüz görülmedi (uygulamada arka plan servisi açık mı?)")
            # Telefon hiç görülmese de kilit durumunu izle (kilitliyken ilk yaklaşma için)
            if tick % 4 == 0 and mon.poll_lock_state() == "locked":
                mon.locked = True
            continue
        try:
            await mon.step(now, tick)
        except Exception as e:  # noqa: BLE001
            print(f"İzleme hatası: {e}")


# ---------- Kalibrasyon ----------

async def calibrate():
    from bleak import BleakScanner

    samples = []
    collecting = {"on": False}

    def cb(dev, adv):
        uuids = [u.lower() for u in (adv.service_uuids or [])]
        if (SERVICE_UUID in uuids or MOVING_UUID in uuids) and adv.rssi is not None:
            if collecting["on"]:
                samples.append(adv.rssi)

    scanner = BleakScanner(cb, service_uuids=[SERVICE_UUID, MOVING_UUID])
    await scanner.start()

    async def measure(seconds):
        samples.clear()
        collecting["on"] = True
        await asyncio.sleep(seconds)
        collecting["on"] = False
        return list(samples)

    print("KALİBRASYON")
    print("Telefonda uygulamanın arka plan servisi açık olmalı.\n")
    await asyncio.to_thread(
        input, "1) Telefonla bilgisayarın başında, oturduğun gibi dur ve Enter'a bas: "
    )
    print("Ölçülüyor (8 sn), telefonu sabit tut...")
    near = await measure(8)
    await asyncio.to_thread(
        input,
        "2) Şimdi kilitlenmesini istediğin mesafeye git (örn. masadan kalkıp 1-2 m uzağa),\n"
        "   telefonu normal tuttuğun gibi tut ve Enter'a bas: ",
    )
    print("Ölçülüyor (8 sn), orada sabit dur...")
    far = await measure(8)
    await scanner.stop()

    if len(near) < 5 or len(far) < 5:
        print(f"Yeterli örnek yok (yakın: {len(near)}, uzak: {len(far)}). "
              "Telefonun servisi açık mı? Tekrar dene.")
        return
    near_med = statistics.median(near)
    far_med = statistics.median(far)
    print(f"\nYakın ortanca: {near_med:.0f} dBm ({len(near)} örnek)")
    print(f"Uzak ortanca : {far_med:.0f} dBm ({len(far)} örnek)")
    if near_med - far_med < 6:
        print("UYARI: iki mesafe arasındaki fark çok az (<6 dB). Bluetooth sinyali bu ortamda "
              "güvenilir ayrım yapmıyor olabilir; yine de değerler kaydedildi.")
    lock_rssi = int(round(far_med + 1))
    near_rssi = int(round(min(near_med - 2, lock_rssi + 8)))
    near_rssi = max(near_rssi, lock_rssi + 3)
    c = load_config()
    c["lock_rssi"] = lock_rssi
    c["near_rssi"] = near_rssi
    c.pop("rssi_threshold", None)
    save_config(c)
    print(f"\nKaydedildi: lock_rssi = {lock_rssi}, near_rssi = {near_rssi}")
    print("Ajanı normal başlat: python agent.py   (debug: true ile RSSI değerlerini izleyebilirsin)")


# ---------- Eklenti HTTP ----------

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _cors(self):
        origin = self.headers.get("Origin", "")
        if origin.startswith("chrome-extension://"):
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Access-Control-Allow-Headers", "X-Token, Content-Type")
            self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")

    def _reply(self, code):
        self.send_response(code)
        self._cors()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_OPTIONS(self):
        self._reply(204)

    def do_POST(self):
        if self.path != "/login":
            return self._reply(404)

        token = self.headers.get("X-Token", "")
        if not hmac.compare_digest(token.encode(), cfg["ext_token"].encode()):
            return self._reply(403)

        # Web siteleri Origin başlığını sahteleyemez; sadece eklentiden gelen istekleri kabul et.
        origin = self.headers.get("Origin", "")
        if origin and not origin.startswith("chrome-extension://"):
            return self._reply(403)

        try:
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(min(length, 2048)))
            host = str(body.get("host", "")).strip().lower()
            stage = str(body.get("stage", ""))
        except Exception:  # noqa: BLE001
            return self._reply(400)

        if not host or len(host) > 253 or "|" in host or stage not in STAGES:
            return self._reply(400)

        asyncio.run_coroutine_threadsafe(send_login(host, stage), loop)
        self._reply(202)


async def main():
    global loop, send_lock, prox, cfg
    from bleak import BleakScanner

    cfg = load_config()
    loop = asyncio.get_running_loop()
    send_lock = asyncio.Lock()
    profile = PROFILES.get(cfg["sensitivity"], PROFILES["fast"])
    prox = Proximity(cfg["lock_rssi"], cfg["near_rssi"], profile)

    server = ThreadingHTTPServer(("127.0.0.1", int(cfg["http_port"])), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()

    scanner = BleakScanner(on_detect, service_uuids=[SERVICE_UUID, MOVING_UUID])
    await scanner.start()

    print("Ajan çalışıyor (sürüm 0.3).")
    print(f"  Eklenti adresi : http://127.0.0.1:{cfg['http_port']}/login")
    print(f"  Eklenti TOKEN  : {cfg['ext_token']}")
    print(f"  Kilit koruması : {'açık' if cfg['lock_enabled'] else 'kapalı'}"
          f" (kilit {cfg['lock_rssi']} dBm, yaklaşma {cfg['near_rssi']} dBm, "
          f"hassasiyet {cfg['sensitivity']})")
    print(f"  Oturum açma    : {'açık' if cfg['unlock_support'] else 'kapalı'}")
    if not cfg["phone_key_hex"]:
        print("  UYARI: phone_key_hex boş. Otomatik giriş ve oturum açma için telefondaki "
              "anahtarı agent_config.json'a yapıştır.")
    try:
        await monitor_loop()
    finally:
        await scanner.stop()
        server.shutdown()


if __name__ == "__main__":
    try:
        if "--calibrate" in sys.argv:
            asyncio.run(calibrate())
        else:
            asyncio.run(main())
    except KeyboardInterrupt:
        print("Çıkılıyor")
