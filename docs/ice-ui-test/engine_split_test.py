"""Проверка маршрутизации на реальном ядре Xray (из Windows-клиента) с правилами и списками ИЗ ANDROID-ИСХОДНИКОВ.
Схема: curl → socks (client xray, правила приложения) → [direct | proxy → «VPN-сервер» (второй xray)].
Фактический маршрут — по access-логам обоих процессов."""
import json, os, re, shutil, subprocess, sys, time, hashlib

ROOT = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(ROOT, 'enginetest'); os.makedirs(WORK, exist_ok=True)
XRAY = r'C:\Users\ELMAR\Documents\sc_artifact_1.6.0\portable\core\xray.exe'
APP = r'C:\Users\ELMAR\Documents\siberia-connect-android\V2rayNG\app\src\main'
KT = open(APP + r'\java\com\v2ray\ang\ui\sc\ScActivity.kt', encoding='utf-8').read()


def kt_json(name):
    m = re.search(r'const val %s = """(.*?)"""' % name, KT, re.S)
    return json.loads(m.group(1))


def to_rules(items):
    out = []
    for r in items:
        r = {k: v for k, v in r.items() if k != 'remarks'}
        r['type'] = 'field'
        out.append(r)
    return out


RU, BASE, SAFE = to_rules(kt_json('RU_ROUTING')), to_rules(kt_json('BASE_ROUTING')), to_rules(kt_json('SAFE_ROUTING'))
SOCKS_CLIENT, SOCKS_SERVER = 18808, 18809

# ассеты: geo-файлы из APK (то, что приложение раскладывает в userAssetPath)
ASSETS = os.path.join(WORK, 'assets_apk'); os.makedirs(ASSETS, exist_ok=True)
for n in ('geoip.dat', 'geosite.dat'):
    shutil.copy(os.path.join(APP, 'assets', n), os.path.join(ASSETS, n))
EMPTY = os.path.join(WORK, 'assets_missing'); os.makedirs(EMPTY, exist_ok=True)   # списков нет


def cfg(rules, logfile, role):
    inb = [{'tag': 'socks-in', 'port': SOCKS_CLIENT if role == 'client' else SOCKS_SERVER, 'listen': '127.0.0.1', 'protocol': 'socks',
            'settings': {'auth': 'noauth', 'udp': False},
            'sniffing': {'enabled': True, 'destOverride': ['http', 'tls', 'quic']}}]     # как в v2ray_config.json приложения
    if role == 'server':
        outs = [{'tag': 'direct', 'protocol': 'freedom'}]
        routing = {'domainStrategy': 'AsIs', 'rules': []}
    else:
        outs = [{'tag': 'proxy', 'protocol': 'socks', 'settings': {'servers': [{'address': '127.0.0.1', 'port': SOCKS_SERVER}]}},   # «VPN» — первый outbound = маршрут по умолчанию
                {'tag': 'direct', 'protocol': 'freedom'}, {'tag': 'block', 'protocol': 'blackhole'}]
        routing = {'domainStrategy': 'AsIs', 'rules': rules}       # AsIs — значение по умолчанию в приложении
    return {'log': {'loglevel': 'info', 'access': logfile}, 'inbounds': inb, 'outbounds': outs, 'routing': routing}


def start(name, conf, assets):
    p = os.path.join(WORK, name + '.json'); json.dump(conf, open(p, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    env = dict(os.environ, XRAY_LOCATION_ASSET=assets)
    err = open(os.path.join(WORK, name + '.err'), 'w')
    return subprocess.Popen([XRAY, 'run', '-c', p], env=env, stdout=err, stderr=subprocess.STDOUT)


def curl(host, ip, port=443, sni=True):
    url = f'https://{host}/' if sni else f'https://{ip}/'
    args = ['curl', '-sS', '-k', '-m', '4', '-o', os.devnull, '-x', f'socks5://127.0.0.1:{SOCKS_CLIENT}']
    if sni: args += ['--resolve', f'{host}:{port}:{ip}']
    subprocess.run(args + [url], capture_output=True)


# цели: (подпись, хост, IP, есть ли SNI)
TARGETS = [
    ('yandex.ru (RU-домен, RU-IP)',          'yandex.ru',            '77.88.55.88',   True),
    ('example.ru (RU-домен, зарубежный IP)', 'example.ru',           '93.184.216.34', True),
    ('vk.com (список, зарубежный IP)',       'vk.com',               '93.184.216.34', True),
    ('example.com (вне правил, США)',        'example.com',          '93.184.216.34', True),
    ('www.google.com (вне правил, США)',     'www.google.com',       '142.250.185.68', True),
    ('77.88.55.88 (RU-IP без имени)',        '77.88.55.88',          '77.88.55.88',   False),
    ('1.1.1.1 (зарубежный IP без имени)',    '1.1.1.1',              '1.1.1.1',       False),
    ('192.168.1.1 (локальная сеть)',         '192.168.1.1',          '192.168.1.1',   False),
]


def run_scenario(title, rules, assets):
    for f in ('client.log', 'server.log'):
        open(os.path.join(WORK, f), 'w').close()
    srv = start('server', cfg([], os.path.join(WORK, 'server.log'), 'server'), assets if assets != EMPTY else ASSETS)
    cli = start('client', cfg(rules, os.path.join(WORK, 'client.log'), 'client'), assets)
    time.sleep(2.5)
    if cli.poll() is not None:
        err = open(os.path.join(WORK, 'client.err')).read().strip().splitlines()
        srv.kill()
        return {'title': title, 'started': False, 'error': (err[-1] if err else 'exit %s' % cli.returncode)[:200]}
    for _, host, ip, sni in TARGETS:
        curl(host, ip, sni=sni)
    time.sleep(1.5)
    log = open(os.path.join(WORK, 'client.log'), encoding='utf-8', errors='ignore').read().splitlines()
    srvlog = open(os.path.join(WORK, 'server.log'), encoding='utf-8', errors='ignore').read()
    cli.kill(); srv.kill()
    acc = [l for l in log if ' accepted ' in l]
    srv_acc = [l for l in srvlog.splitlines() if ' accepted ' in l]
    rows = []
    for i, (label, host, ip, sni) in enumerate(TARGETS):
        m = re.search(r'\[socks-in (?:->|>>) (\w+)\]', acc[i]) if i < len(acc) else None
        tag = m.group(1) if m else '-'
        key = ('tcp:%s:443' % host) if sni else ('tcp:%s:443' % ip)
        via_tunnel = any(key in l for l in srv_acc)       # запрос дошёл до «VPN-сервера» = шёл через туннель
        rows.append({'target': label, 'engine': tag, 'arrived_at_vpn_server': via_tunnel})
    return {'title': title, 'started': True, 'rows': rows}


def sha(p): return hashlib.sha256(open(p, 'rb').read()).hexdigest()[:16]


out = {'xray': subprocess.run([XRAY, '-version'], capture_output=True, text=True).stdout.splitlines()[0],
       'geoip_sha': sha(ASSETS + r'\geoip.dat'), 'geosite_sha': sha(ASSETS + r'\geosite.dat'), 'scenarios': []}
out['scenarios'].append(run_scenario('A. Умное разделение (RU_ROUTING + списки из APK)', RU, ASSETS))
out['scenarios'].append(run_scenario('B. Весь трафик через VPN (BASE_ROUTING)', BASE, ASSETS))
out['scenarios'].append(run_scenario('C. Умные правила, но списков нет (то, что было бы без защиты)', RU, EMPTY))
out['scenarios'].append(run_scenario('D. Списков нет → приложение берёт SAFE_ROUTING (без geo)', SAFE, EMPTY))
json.dump(out, open(os.path.join(WORK, 'result.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print(out['xray'], '| geoip', out['geoip_sha'], '| geosite', out['geosite_sha'])
for sc in out['scenarios']:
    print('\n' + sc['title'])
    if not sc['started']:
        print('   ЯДРО НЕ ЗАПУСТИЛОСЬ:', sc['error']); continue
    for r in sc['rows']:
        print('   %-42s -> %-7s | дошло до VPN-сервера: %s' % (r['target'], r['engine'], 'да' if r['arrived_at_vpn_server'] else 'нет'))
