#!/usr/bin/env python3
"""
Laedt die Tempolimits (OpenStreetMap maxspeed) fuer ein Gebiet auf dem Mac herunter, schreibt sie im
Kachel-Format des ZFix-Launchers (ab 2.2) und kopiert sie per adb aufs Radio.

Aufruf:  python3 tempolimit-karten.py [IP:Port]
Gebiet aendern:  --bbox SUED,WEST,NORD,OST   (Standard: 100 km rund um Ostschweiz und Bodensee)
Nur herunterladen, nicht kopieren:  --no-push
"""
import argparse, json, math, os, struct, subprocess, sys, time, urllib.parse, urllib.request

TILE_LAT, TILE_LON = 0.05, 0.075          # muss zu LimitCache.java passen
CHUNK = 4                                 # 4 x 4 Kacheln pro Abfrage beim Overpass-Server
DEST = "/sdcard/Android/data/ch.zfix.launcher/files/limits"
# mehrere oeffentliche Server; ist einer ueberlastet, wird der naechste versucht
URLS = ["https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"]


def parse_maxspeed(v):                    # gleiche Regeln wie MainActivity.parseMaxspeed
    if not v:
        return -1
    v = v.strip(); lv = v.lower()
    if lv.endswith(":urban"): return 50
    if lv in ("ch:rural", "at:rural"): return 100 if lv.startswith("at") else 80
    if lv.endswith(":rural"): return 100
    if lv == "ch:trunk": return 100
    if lv in ("ch:motorway", "at:motorway"): return 130 if lv.startswith("at") else 120
    if lv.endswith(":living_street") or lv == "walk": return 20
    digits = ""
    for ch in v:
        if ch.isdigit(): digits += ch
        elif digits: break
    if not digits: return -1
    n = int(digits)
    if "mph" in lv: n = round(n * 1.609)
    return n


def post(url, data):
    # curl zuerst: das Python auf dem Mac ist teils zu alt fuer die Verschluesselung der Server
    try:
        out = subprocess.run(["curl", "-sS", "--fail", "-m", "240", "-A", "ZFix-Launcher-Mac/2.6",
                              "--data-binary", "@-", url], input=data, capture_output=True, check=True).stdout
        return json.loads(out)
    except FileNotFoundError:
        req = urllib.request.Request(url, data=data, headers={"User-Agent": "ZFix-Launcher-Mac/2.6"})
        with urllib.request.urlopen(req, timeout=240) as r:
            return json.load(r)


ESTIMATE = 1000                           # gespeichertes Limit + 1000 = nur geschaetzt (wie LimitCache.ESTIMATE)


def guess(highway):                       # gleiche Regeln wie LimitCache.guess
    if highway == "living_street": return ESTIMATE + 20
    if highway == "residential": return ESTIMATE + 30
    return -1


def fetch(s, w, n, e):
    bb = "(%.5f,%.5f,%.5f,%.5f)" % (s, w, n, e)
    # Strassen mit Tempolimit, dazu Quartierstrassen ohne Eintrag (bekommen eine geschaetzte 30)
    q = ("[out:json][timeout:180];(way[highway][maxspeed]%s;"
         "way[highway~\"^(residential|living_street)$\"][!maxspeed]%s;);out tags geom;" % (bb, bb))
    data = urllib.parse.urlencode({"data": q}).encode()
    for attempt in range(8):
        url = URLS[attempt % len(URLS)]
        try:
            return post(url, data).get("elements", [])
        except Exception as ex:
            wait = 10 * (attempt // len(URLS) + 1)
            print("\n   %s: Fehler (%s), neuer Versuch in %d s" % (url.split("/")[2], str(ex)[:80], wait))
            time.sleep(wait)
    raise SystemExit("Kein Overpass-Server antwortet, bitte spaeter nochmals starten (fertige Kacheln bleiben).")


def bar(done, total):
    w = 30
    k = int(w * done / total) if total else w
    sys.stdout.write("\r[%s%s] %3d%%  %d/%d Kacheln" % ("#" * k, "-" * (w - k), 100 * done // max(total, 1), done, total))
    sys.stdout.flush()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("target", nargs="?", help="IP:Port aus Wireless Debugging (optional, wenn schon verbunden)")
    ap.add_argument("--bbox", default="46.45,8.19,48.25,10.86")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "tempolimit-kacheln"))
    ap.add_argument("--no-push", action="store_true")
    ap.add_argument("--neu", action="store_true", help="vorhandene Kacheln trotzdem neu laden (z.B. nach einer neuen Version)")
    a = ap.parse_args()
    s, w, n, e = [float(x) for x in a.bbox.split(",")]
    y0, y1 = math.floor(s / TILE_LAT), math.floor(n / TILE_LAT)
    x0, x1 = math.floor(w / TILE_LON), math.floor(e / TILE_LON)
    os.makedirs(a.out, exist_ok=True)
    total = (y1 - y0 + 1) * (x1 - x0 + 1)
    print("Gebiet: %d Kacheln, Abfragen in Bloecken zu %dx%d" % (total, CHUNK, CHUNK))

    for cy in range(y0, y1 + 1, CHUNK):
        for cx in range(x0, x1 + 1, CHUNK):
            ys = range(cy, min(cy + CHUNK, y1 + 1))
            xs = range(cx, min(cx + CHUNK, x1 + 1))
            def fertig(y, x):
                f = os.path.join(a.out, "%d_%d.bin" % (y, x))
                # mit --neu zaehlen nur Kacheln der letzten 24 h als fertig, so kann ein abgebrochener Lauf weitermachen
                return os.path.exists(f) and (not a.neu or time.time() - os.path.getmtime(f) < 86400)
            if all(fertig(y, x) for y in ys for x in xs):
                continue
            bs, bw = ys[0] * TILE_LAT, xs[0] * TILE_LON
            bn, be = (ys[-1] + 1) * TILE_LAT, (xs[-1] + 1) * TILE_LON
            ways = []
            for el in fetch(bs, bw, bn, be):
                tags = el.get("tags") or {}
                lim = parse_maxspeed(tags.get("maxspeed"))
                if lim <= 0:
                    lim = guess(tags.get("highway"))
                g = el.get("geometry") or []
                if lim > 0 and 1 <= len(g) < 65535:
                    ways.append((lim, [(p["lat"], p["lon"]) for p in g]))
            tiles = {(y, x): [] for y in ys for x in xs}
            m = 0.0005   # Strassen knapp ausserhalb trotzdem mitnehmen (30-m-Suche am Kachelrand)
            for lim, pts in ways:
                la = [p[0] for p in pts]; lo = [p[1] for p in pts]
                for y in range(math.floor((min(la) - m) / TILE_LAT), math.floor((max(la) + m) / TILE_LAT) + 1):
                    for x in range(math.floor((min(lo) - m) / TILE_LON), math.floor((max(lo) + m) / TILE_LON) + 1):
                        if (y, x) in tiles:
                            tiles[(y, x)].append((lim, pts))
            for (y, x), tw in tiles.items():
                with open(os.path.join(a.out, "%d_%d.bin" % (y, x)), "wb") as f:
                    f.write(struct.pack(">i", len(tw)))
                    for lim, pts in tw:
                        f.write(struct.pack(">hH", lim, len(pts)))   # Schaetzungen: 1020/1030
                        for la, lo in pts:
                            f.write(struct.pack(">ff", la, lo))
            done = len([f for f in os.listdir(a.out) if f.endswith(".bin")])
            bar(done, total)
            time.sleep(5)   # den oeffentlichen Server nicht ueberlasten

    count = len([f for f in os.listdir(a.out) if f.endswith(".bin")])
    bar(count, total)
    print()
    print("Fertig: %d Kacheln in %s" % (count, a.out))
    if a.no_push:
        return
    adb = os.environ.get("ADB", "adb")
    if a.target:
        subprocess.call([adb, "connect", a.target])
    subprocess.check_call([adb, "shell", "mkdir", "-p", DEST])
    subprocess.check_call([adb, "push", a.out + "/.", DEST])
    print("Auf das Radio kopiert. Im ZFix-Menue (lange auf ZFIX druecken) steht jetzt die Anzahl Kacheln.")


if __name__ == "__main__":
    main()
