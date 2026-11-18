#!/usr/bin/env python3
"""Schreibt versionen.json fuer ein GitHub-Release: python3 tools/versionen.py out/ZFix-Launcher-3.0.apk ZFix-DAB.apk ...
Paketname/Version kommen aus `aapt2 dump badging` (aapt2 in $SDK oder im PATH)."""
import hashlib, json, os, re, subprocess, sys
NAMES = {"ch.zfix.launcher": "ZFix Launcher", "ch.zfix.dab": "ZFix DAB", "ch.zfix.radio": "ZFix Radio", "ch.zfix.connect": "ZFIX Connect"}
aapt2 = os.path.join(os.environ.get("SDK", os.path.expanduser("~/sdk")), "aapt2")
if not os.path.exists(aapt2): aapt2 = "aapt2"
apps = []
for f in sys.argv[1:]:
    b = subprocess.run([aapt2, "dump", "badging", f], capture_output=True, text=True).stdout
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", b)
    apps.append({"paket": m[1], "name": NAMES.get(m[1], m[1]), "version": m[3], "code": int(m[2]),
                 "datei": os.path.basename(f), "bytes": os.path.getsize(f), "sha256": hashlib.sha256(open(f, "rb").read()).hexdigest()})
json.dump({"apps": apps}, open("versionen.json", "w"), indent=2, ensure_ascii=False)
print(json.dumps({"apps": apps}, indent=2, ensure_ascii=False))
