#!/usr/bin/env python3
"""E2E в Android-эмуляторе: публичная 1.3.1 → обновление поверх (adb install -r, без удаления данных) → кандидат 1.4.0.
Секреты: ключ/токен аккаунта живут только на эмуляторе; скрипт не читает хранилище учётных данных, не печатает содержимое prefs
(только перечисленные несекретные ключи), а логи и дампы интерфейса перед сохранением очищаются от длинных токенов и ссылок.
Переменные: PUBLIC_APK, NEW_APK, OUT, E2E_NOACCOUNT=1 (отладка: аккаунт не создаётся, сценарии с VPN пропускаются)."""
import json, os, re, subprocess, sys, time
import xml.etree.ElementTree as ET

PKG = "com.siberiaconnect.vpn"
PUB, NEW, OUT = os.environ["PUBLIC_APK"], os.environ["NEW_APK"], os.environ.get("OUT", "e2e_out")
NOACC = os.environ.get("E2E_NOACCOUNT") == "1"
os.makedirs(OUT, exist_ok=True)
RES = []
SCRUB = [(re.compile(r"[A-Za-z0-9_\-]{24,}"), "<token>"), (re.compile(r"(vless|vmess|trojan|ss)://\S+"), "<key>"),
         (re.compile(r"https?://\S*(token|sub|key|auth)\S*", re.I), "<url>")]


def scrub(s):
    for rx, rep in SCRUB:
        s = rx.sub(rep, s)
    return s


def adb(*a, timeout=120, check=False):
    p = subprocess.run(["adb"] + list(a), capture_output=True, text=True, timeout=timeout, errors="replace")
    return (p.stdout or "") + ("" if check else "")


def sh(cmd, timeout=120):
    return adb("shell", cmd, timeout=timeout)


def log(msg):
    print(msg, flush=True)


def rec(sid, title, ok, detail=""):
    RES.append({"id": sid, "title": title, "ok": bool(ok), "detail": detail})
    log("[%s] %s — %s %s" % ("OK" if ok else "FAIL", sid, title, ("| " + detail) if detail else ""))


def shot(name):
    p = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60)
    open(os.path.join(OUT, name + ".png"), "wb").write(p.stdout)


def dump():
    for _ in range(5):
        sh("uiautomator dump /sdcard/u.xml")
        raw = subprocess.run(["adb", "exec-out", "cat", "/sdcard/u.xml"], capture_output=True, timeout=60).stdout
        try:
            root = ET.fromstring(raw)
        except ET.ParseError:
            time.sleep(1.5); continue
        nodes = []
        for n in root.iter("node"):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
            if not m: continue
            nodes.append({"text": n.get("text", ""), "desc": n.get("content-desc", ""), "b": tuple(map(int, m.groups())),
                          "click": n.get("clickable") == "true", "checked": n.get("checked") == "true", "cls": n.get("class", "")})
        if nodes: return nodes
        time.sleep(1.5)
    return []


def labels(nodes):
    return [x for n in nodes for x in (n["text"], n["desc"]) if x]


def find(nodes, rx, exact=False):
    f = re.fullmatch if exact else re.search
    return [n for n in nodes if f(rx, n["text"] or "") or f(rx, n["desc"] or "")]


def tap(n):
    l, t, r, b = n["b"]
    sh("input tap %d %d" % ((l + r) // 2, (t + b) // 2))


def wait_for(rx, timeout=60, exact=True):
    end = time.time() + timeout
    while time.time() < end:
        nodes = dump()
        hit = find(nodes, rx, exact)
        if hit: return nodes, hit
        time.sleep(2)
    return dump(), []


def save_ui(name):
    nodes = dump()
    open(os.path.join(OUT, name + ".txt"), "w", encoding="utf-8").write(scrub("\n".join(labels(nodes))))
    return nodes


def http_get(host, path):
    cmd = "printf 'GET %s HTTP/1.0\\r\\nHost: %s\\r\\nUser-Agent: curl/8\\r\\n\\r\\n' | nc -w 10 %s 80" % (path, host, host)
    out = sh(cmd, timeout=40)
    body = out.split("\r\n\r\n", 1)[-1] if "\r\n\r\n" in out else out.split("\n\n", 1)[-1]
    m = re.search(r"(\d{1,3}(?:\.\d{1,3}){3})", body)
    return m.group(1) if m else None


NONRU = ("api.ipify.org", "/")
RU = ("ipv4-internet.yandex.net", "/api/v0/ip")


def egress():
    return {"nonru": http_get(*NONRU), "ru": http_get(*RU)}


def tun_up():
    return bool(re.search(r"\btun\d*\b", sh("ip -o link show")))


def prefs_keys(keys):
    txt = sh("cat /data/data/%s/shared_prefs/sc_app.xml" % PKG)
    out = {}
    for k in keys:
        m = re.search(r'<(boolean|string|int|long)\s+name="%s"\s+value="([^"]*)"' % re.escape(k), txt)
        if m: out[k] = m.group(2)
        elif re.search(r'<set\s+name="%s"' % re.escape(k), txt): out[k] = "<set>"
    return out


def data_files():
    out = sh("cd /data/data/%s && find shared_prefs files -type f 2>/dev/null | sort" % PKG)
    return [l.strip() for l in out.splitlines() if l.strip()]


def pkg_version():
    out = sh("dumpsys package %s | grep -E 'versionCode|versionName' | head -3" % PKG)
    return re.sub(r"\s+", " ", out.strip())


def start(act):
    sh("am start -n %s/%s" % (PKG, act))


def stop_app():
    sh("am force-stop %s" % PKG)


def main():
    # ── подготовка эмулятора
    adb("wait-for-device", timeout=300)
    for _ in range(90):
        if sh("getprop sys.boot_completed").strip() == "1": break
        time.sleep(3)
    adb("root"); adb("wait-for-device"); time.sleep(3)
    for s in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        sh("settings put global %s 0" % s)
    sh("svc power stayon true"); sh("input keyevent 82")
    log("android %s, sdk %s" % (sh("getprop ro.build.version.release").strip(), sh("getprop ro.build.version.sdk").strip()))
    base = egress()
    rec("S0", "эмулятор: сеть и базовый внешний адрес (без VPN)", bool(base["nonru"]), "nonru=%s ru=%s" % (base["nonru"], base["ru"]))
    # ── 1. публичная 1.3.1
    out = adb("install", "-r", PUB, timeout=300)
    rec("S1", "установка публичной 1.3.1", "Success" in out, pkg_version())
    pub_ver = pkg_version()
    sh("appops set %s ACTIVATE_VPN allow" % PKG)
    sh("pm grant %s android.permission.POST_NOTIFICATIONS" % PKG)
    if NOACC:
        xml = '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n<boolean name="auto_reg_done" value="true" />\n</map>\n'
        sh("mkdir -p /data/data/%s/shared_prefs" % PKG)
        open("seed.xml", "w").write(xml); adb("push", "seed.xml", "/data/local/tmp/sc_app.xml")
        sh("cp /data/local/tmp/sc_app.xml /data/data/%s/shared_prefs/sc_app.xml && rm /data/local/tmp/sc_app.xml" % PKG)
        uid = re.search(r"userId=(\d+)", sh("dumpsys package %s | grep userId" % PKG))
        if uid: sh("chown %s:%s /data/data/%s/shared_prefs/sc_app.xml; restorecon /data/data/%s/shared_prefs/sc_app.xml" % (uid.group(1), uid.group(1), PKG, PKG))
    start("com.v2ray.ang.ui.sc.ScActivity")
    time.sleep(35 if not NOACC else 15)
    shot("01_public_first_launch"); save_ui("01_public_first_launch")
    files0 = data_files()
    rec("S2", "публичная 1.3.1 запускается, хранилище создано", any("sc_secure" in f for f in files0), "файлов в данных: %d" % len(files0))
    connected_pub = False
    if not NOACC:
        # ── 2. подключение в 1.3.1 (ключ получен самим приложением при первом запуске)
        start("com.v2ray.ang.ui.shortcut.ScStartActivity")
        t0 = time.time()
        while time.time() - t0 < 60 and not tun_up(): time.sleep(2)
        time.sleep(6)
        shot("02_public_connecting")
        # окно разрешения VPN (если appops не помог)
        n = dump(); ok_btn = find(n, r"^(OK|ОК|Ok)$", True)
        if ok_btn: tap(ok_btn[0]); time.sleep(10)
        connected_pub = tun_up()
        e = egress() if connected_pub else {}
        rec("S3", "1.3.1: VPN поднят, трафик идёт через туннель", connected_pub and e.get("nonru") and e["nonru"] != base["nonru"],
            "nonru=%s (без VPN %s)" % (e.get("nonru"), base["nonru"]))
        start("com.v2ray.ang.ui.shortcut.ScStopActivity"); time.sleep(8)
        rec("S4", "1.3.1: VPN отключается, адрес вернулся", (not tun_up()) and egress()["nonru"] == base["nonru"], "tun=%s" % tun_up())
    # ── 3. маркер настроек и снимок до обновления
    stop_app(); time.sleep(2)
    txt = sh("cat /data/data/%s/shared_prefs/sc_app.xml" % PKG)
    if "</map>" in txt:
        sh("sed -i 's#</map>#<string name=\"e2e_marker\" value=\"kept\" />\\n</map>#' /data/data/%s/shared_prefs/sc_app.xml" % PKG)
    keys = ["ru_direct", "auto", "defaults_v6", "split_mode", "e2e_marker", "direct_removed"]
    before = prefs_keys(keys); files_before = data_files()
    log("настройки до обновления: %s" % before)
    # ── 4. обновление поверх
    out = adb("install", "-r", NEW, timeout=300)
    rec("S5", "обновление 1.3.1 → 1.4.0 поверх (install -r, без удаления данных)", "Success" in out, scrub(out.strip())[:160] + " | " + pkg_version())
    after = prefs_keys(keys); files_after = data_files()
    kept = all(f in files_after for f in files_before if "sc_secure" in f or f.endswith("sc_app.xml") or "mmkv" in f)
    rec("S6", "данные приложения сохранены (файлы хранилищ, маркер настроек)", kept and after.get("e2e_marker") == "kept",
        "ключи до=%s после=%s" % (before, after))
    # ── 5. новый интерфейс
    start("com.v2ray.ang.ui.sc.ScActivity"); time.sleep(20)
    shot("03_new_first_launch")
    nodes, hit = wait_for(r"Серверы", 40)
    L = labels(nodes)
    is_ice = bool(find(nodes, r"^Серверы$", True)) and bool(find(nodes, r"^Настройки$", True)) and bool(find(nodes, r"^(Подключиться|Отменить|Отключиться|Повторить)$", True))
    save_ui("03_new_first_launch")
    rec("S7", "после обновления открывается интерфейс «Сибирский хребет»", is_ice, "метки: " + ", ".join(sorted(set(L))[:14]))
    stop_app(); time.sleep(2); start("com.v2ray.ang.ui.sc.ScActivity"); time.sleep(15)
    nodes, hit = wait_for(r"^(Подключиться|Отключиться|Отменить|Повторить)$", 40)
    shot("04_new_relaunch")
    rec("S8", "повторное открытие: снова новый интерфейс", bool(hit) and bool(find(nodes, r"^Серверы$", True)))
    if NOACC:
        rec("S9", "сценарии с VPN пропущены (отладочный прогон без аккаунта)", True, "E2E_NOACCOUNT=1")
        return
    # подписка сохранилась: нет «Нужен вход/ключ», серверы видны
    tap(find(nodes, r"^Серверы$", True)[0]); time.sleep(4)
    nodes = dump(); shot("05_servers"); save_ui("05_servers")
    countries = [t for t in ("Нидерланды", "Финляндия", "Россия", "Казахстан") if find(nodes, t)]
    rec("S9", "подписка сохранилась: список серверов на месте, входа не требуют", len(countries) >= 1 and not find(nodes, r"Нужен (вход|ключ)"), "страны: %s" % countries)
    # ── 6. подключение в новом интерфейсе
    tap(find(dump(), r"^Главная$", True)[0]); time.sleep(3)
    nodes = dump(); btn = find(nodes, r"^Подключиться$", True)
    if btn: tap(btn[0])
    n2, ok = wait_for(r"^Подключено$", 90)
    time.sleep(5); shot("06_new_connected")
    conn = bool(ok) and tun_up(); e1 = egress() if conn else {}
    rec("S10", "1.4.0: подключено, VPN поднят, трафик идёт через туннель", conn and e1.get("nonru") and e1["nonru"] != base["nonru"],
        "статус=%s nonru=%s (без VPN %s)" % (bool(ok), e1.get("nonru"), base["nonru"]))
    rec("S11", "умное разделение (по умолчанию): российский адрес напрямую, остальной через VPN",
        conn and e1.get("ru") == base["ru"] and e1.get("nonru") != base["nonru"], "ru=%s nonru=%s base_ru=%s" % (e1.get("ru"), e1.get("nonru"), base["ru"]))
    # ── 7. смена узла
    changed = None
    if conn and len(countries) >= 2:
        tap(find(dump(), r"^Серверы$", True)[0]); time.sleep(3)
        ips = {}
        for c in countries[:2]:
            nodes = dump(); row = [n for n in find(nodes, "^" + c + "$", True)]
            if not row: continue
            tap(row[0]); time.sleep(3)
            n3, ok3 = wait_for(r"^Подключено$", 60); time.sleep(6)
            ips[c] = egress().get("nonru")
            tap(find(dump(), r"^Серверы$", True)[0]); time.sleep(3)
        shot("07_after_node_switch")
        changed = len(set(v for v in ips.values() if v)) >= 2
        rec("S12", "смена узла: трафик выходит через разные узлы", changed, "выходные адреса по странам: %s" % ips)
    else:
        rec("S12", "смена узла", False, "нет подключения или меньше двух стран")
    # ── 8. режим «Весь трафик через VPN»
    tap(find(dump(), r"^Настройки$", True)[0]); time.sleep(3)
    nodes = dump(); save_ui("08_settings"); shot("08_settings")
    sw = [n for n in find(nodes, r"Умное разделение трафика")]
    sw.sort(key=lambda n: n["b"][0], reverse=True)
    mode_before = [n for n in nodes if re.fullmatch(r"(Включено|Выключено|Недоступно)", n["text"] or "")]
    rec("S13", "настройки: режим виден как «Включено»", bool(mode_before) and mode_before[0]["text"] == "Включено", [m["text"] for m in mode_before])
    if sw:
        tap(sw[0]); time.sleep(3)
        n4, ok4 = wait_for(r"^Выключено$", 15)
        n5, ok5 = wait_for(r"^Подключено$", 90) if False else (None, True)
        time.sleep(12)                                                 # VPN перезапускается при смене режима
        shot("09_mode_all")
        e2 = egress()
        rec("S14", "режим «Весь трафик через VPN»: оба адреса идут через туннель", tun_up() and e2.get("ru") and e2["ru"] != base["ru"] and e2.get("nonru") != base["nonru"],
            "ru=%s nonru=%s base_ru=%s" % (e2.get("ru"), e2.get("nonru"), base["ru"]))
        stop_app(); time.sleep(2); start("com.v2ray.ang.ui.sc.ScActivity"); time.sleep(15)
        n6 = dump(); tap(find(n6, r"^Настройки$", True)[0]); time.sleep(3); n6 = dump()
        st = [n["text"] for n in n6 if re.fullmatch(r"(Включено|Выключено|Недоступно)", n["text"] or "")]
        rec("S15", "режим сохранился после перезапуска приложения", bool(st) and st[0] == "Выключено", st)
        sw2 = sorted(find(n6, r"Умное разделение трафика"), key=lambda n: n["b"][0], reverse=True)
        if sw2:
            tap(sw2[0]); time.sleep(15)
            e3 = egress()
            rec("S16", "режим «Умное разделение» включён обратно: российский адрес снова напрямую", tun_up() and e3.get("ru") == base["ru"] and e3.get("nonru") != base["nonru"],
                "ru=%s nonru=%s" % (e3.get("ru"), e3.get("nonru")))
    else:
        rec("S14", "переключатель режима не найден в интерфейсе", False)
    # ── 9. отключение
    tap(find(dump(), r"^Главная$", True)[0]); time.sleep(3)
    nodes = dump(); b = find(nodes, r"^(Отключиться|Отменить)$", True)
    if b: tap(b[0])
    n7, ok7 = wait_for(r"^Отключено$", 40); time.sleep(5); shot("10_new_disconnected")
    rec("S17", "отключение: статус «Отключено», туннель снят, адрес вернулся", bool(ok7) and not tun_up() and egress()["nonru"] == base["nonru"], "tun=%s" % tun_up())


try:
    main()
except Exception as e:
    rec("EXC", "сбой сценария", False, scrub(repr(e))[:300])
finally:
    logc = sh("logcat -d -t 4000 | grep -E 'ScActivity|ScAccount|AndroidRuntime|FATAL|Xray|libv2ray|VpnService|SiberiaConnect' | tail -n 150")
    open(os.path.join(OUT, "logcat_filtered.txt"), "w", encoding="utf-8").write(scrub(logc))
    json.dump(RES, open(os.path.join(OUT, "results.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    bad = [r for r in RES if not r["ok"]]
    log("ИТОГ: %d из %d сценариев пройдено" % (len(RES) - len(bad), len(RES)))
    sys.exit(1 if bad else 0)
