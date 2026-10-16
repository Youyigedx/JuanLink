# Juan LinK 浼氬悎鏈嶅姟鍣紙Rendezvous Server锛?
鎶娿€岄暱杩炴帴涓层€嶆崲鎴愩€? 浣嶇煭閰嶅鐮併€嶇殑杞婚噺淇′护鏈嶅姟銆?
```
A 绔€屽垱寤哄崗浣溿€?鈹€鈹€POST /rendezvous/register鈹€鈹€鈻?鏈嶅姟鍣ㄥ瓨涓嬮厤瀵逛俊鎭紝杩斿洖鐭爜
B 绔€屽姞鍏ュ崗浣溿€?鈹€鈹€GET  /rendezvous/lookup?code鈹€鈹€鈻?鏈嶅姟鍣ㄨ繑鍥為厤瀵逛俊鎭?A銆丅 闅忓悗鐩磋繛锛堝眬鍩熺綉 TCP 鎴?TURN 涓户锛夆攢鈹€ 鏈嶅姟鍣ㄤ笉鍐嶅弬涓?```

闆剁涓夋柟渚濊禆锛屽彧鐢?Python 3 鏍囧噯搴撱€?
---

## 涓€銆佲殸锔?鍏堣锛氳繖鏀瑰彉浜嗗畨鍏ㄦā鍨?
### 1. 鏈嶅姟鍣ㄦ垚涓轰俊浠绘柟锛堣繖鏄究鍒╃殑浠ｄ环锛?
鏀归€犲墠锛屽畨鍏ㄦ€у缓绔嬪湪锛?
> 鍏挜杩涗簩缁寸爜 鈥斺€?**甯﹀鍙俊淇￠亾**锛屾湇鍔″櫒鏃犳硶鏇挎崲鍏挜銆?
鏀归€犲悗锛?*鍏挜鐢辨湇鍔″櫒涓嬪彂**銆傚洜姝ゆ湇鍔″櫒锛堟垨琚敾闄枫€佽鍔寔鐨勬湇鍔″櫒锛?*鍏峰涓棿浜鸿兘鍔?*锛?瀹冨彲浠ュ悜涓ょ鍚勮繑鍥炶嚜宸辩殑鍏挜锛屽缓绔嬩袱鏉′笉鍚岀殑瀵嗛挜锛屼粠鑰岃В瀵嗙敾甯冨唴瀹广€?
> **缁撹锛氥€岀鍒扮鍔犲瘑锛屾湇鍔″櫒鐪嬩笉鍒板唴瀹广€嶈繖鍙ヨ瘽鍦ㄧ煭鐮佹ā寮忎笅涓嶅啀鎴愮珛銆?*
> 瀹冨彧鍦ㄦ壂鐮?/ 浣跨敤杩炴帴涓叉椂鎴愮珛銆?
**鑻ヨ閲嶆柊鑾峰緱鍙獙璇佺殑瀹夊叏鎬?*锛岄渶瑕佸湪鎻℃墜瀹屾垚鍚庢瘮瀵广€岀煭璁よ瘉涓诧紙SAS锛夈€嶁€斺€?鐢变細璇濆瘑閽ユ淳鐢熶竴涓煭鏁板瓧锛屼袱绔樉绀猴紝鐢ㄦ埛瀵逛竴鐪兼槸鍚︿竴鑷淬€?杩欐槸 Signal銆屽畨鍏ㄧ爜銆? Matrix 璁惧楠岃瘉鐨勬垚鐔熷仛娉曘€?**褰撳墠瀹㈡埛绔皻鏈疄鐜?SAS**锛屾湰鏂囦欢濡傚疄璇存槑锛屼笉澶稿ぇ銆?
### 2. 鐭爜绌洪棿灏忥紝鍏綉蹇呴』闄愭祦

鐭爜鏍煎紡 `DDDD-LLLL`锛? 浣嶆暟瀛?+ 3 浣嶅瓧姣?+ 1 浣嶆牎楠屽瓧姣嶏紝**绾?2^21 缁勫悎**銆?鑻ヤ笉闄愭祦锛屾敾鍑昏€呭彲鏋氫妇鐭爜锛屾妸鍒汉鐨勫緟閰嶅璁板綍鎶㈠厛鍙栬蛋銆?
鏈嶅姟绔凡寮哄埗浠ヤ笅缂撹В锛?*榛樿寮€鍚紝鍕块殢鎰忔斁瀹?*锛夛細

| 鏈哄埗 | 榛樿鍊?| 浣滅敤 |
|---|---|---|
| 鐭?TTL | 180 绉?| 缂╃煭鍙敾鍑荤獥鍙?|
| 涓€娆℃€ф秷璐?| 寮哄埗 | 鍙栬蛋鍗冲垹锛屼笖**涓嶉噸鍙?* |
| 鏌ヨ闄愭祦 | 20 娆?鍒嗛挓路IP | 闃绘鏋氫妇 |
| 鐧昏闄愭祦 | 30 娆?鍒嗛挓路IP | 闃茬亴婊?|
| 璁板綍涓婇檺 | 5000 鏉?| 闃插唴瀛樿€楀敖 |
| 缁熶竴 404 | 寮哄埗 | 涓嶅尯鍒嗐€屼笉瀛樺湪/宸茶繃鏈?宸蹭娇鐢ㄣ€嶏紝闃叉帰娴?|

鍚屾椂瀹㈡埛绔晶锛?*鐧昏澶辫触鍗虫姤閿?*锛屼笉浼氱粰浣犱竴涓皝涔熻В鏋愪笉浜嗙殑姝荤爜銆?
### 3. 榛樿鏄庢枃 HTTP 浼氳绐冨惉

娌℃湁鍩熷悕鍜岃瘉涔︽椂璧?`http://`銆傚悓閾捐矾涓婄殑绐冨惉鑰呰兘鐪嬪埌鐭爜锛屼粠鑰屾姠鍏堥厤瀵广€?
**缂撹В**锛氱煭 TTL + 涓€娆℃€?+ 闄愭祦鑳藉ぇ骞呴檷浣庢垚鍔熺巼銆?**鏍规不**锛氭嬁鍒板煙鍚嶅拰璇佷功鍚庢敼鐢?HTTPS锛堣绗洓鑺?Nginx 绀轰緥锛夈€?瀹㈡埛绔缃潰鏉垮湪 `http` 鏃朵細鏄剧ず绾㈠瓧璀﹀憡銆?
---

## 浜屻€佸揩閫熼儴缃诧紙鏃犲煙鍚?/ 鍙湁 IP 鍜岀鍙ｏ級

### 1. 鍚姩

```bash
# 鏈€灏忓惎鍔紙榛樿 0.0.0.0:8080锛孴TL 180s锛?python3 server/rendezvous_server.py

# 鎺ㄨ崘锛氬姞璁块棶浠ょ墝 + 鑷畾涔夌鍙?python3 server/rendezvous_server.py --port 8080 --token "$(openssl rand -hex 16)"
```

鎶?`--token` 鎵撳嵃鍑烘潵鐨勫€煎～鍒板鎴风璁剧疆闈㈡澘鐨勩€岃闂护鐗屻€嶉噷锛屼袱绔竴鑷淬€?
### 2. 鏀捐绔彛

```bash
# Linux锛坒irewalld锛?sudo firewall-cmd --add-port=8080/tcp --permanent && sudo firewall-cmd --reload
# Linux锛坲fw锛?sudo ufw allow 8080/tcp
# 浜戞湇鍔″櫒锛氳繕瑕佸湪鎺у埗鍙板畨鍏ㄧ粍鏀捐 8080
```

### 3. 楠岃瘉

```bash
curl http://<浣犵殑IP>:8080/health
# {"ok": true, "version": 1, "uptimeSec": 3, "active": 0, "capacity": 5000, "ttlSec": 180}
```

### 4. 瀹㈡埛绔厤缃?
鎵撳紑 Juan LinK 鈫掋€岃缃€嶁啋銆屼細鍚堟湇鍔★紙鐭厤瀵圭爜锛夈€嶏細

- 鏈嶅姟鍣ㄥ湴鍧€锛歚<浣犵殑IP>`锛堜笉瑕佸甫 `http://`锛屽鎴风浼氳嚜鍔ㄨˉ锛?- 绔彛锛歚8080`
- 鍗忚锛歚http`
- 璁块棶浠ょ墝锛氫笌 `--token` 涓€鑷达紙娌¤灏辩暀绌猴級

鐐广€屼繚瀛樺苟娴嬭瘯銆嶏紝鏄剧ず銆屸棌 宸茶繛閫氾紝鐭厤瀵圭爜鍙敤銆嶅嵆鎴愬姛銆?
### 5. 甯搁┗杩愯锛坰ystemd锛?
```ini
# /etc/systemd/system/juanlink-rendezvous.service
[Unit]
Description=Juan LinK Rendezvous Server
After=network.target

[Service]
Type=simple
User=www-data
WorkingDirectory=/opt/juanlink
ExecStart=/usr/bin/python3 /opt/juanlink/server/rendezvous_server.py --port 8080
Restart=always
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now juanlink-rendezvous
sudo systemctl status juanlink-rendezvous
```

> 娉ㄦ剰锛?*鍐呭瓨瀛樺偍**锛岄噸鍚悗娲昏穬閰嶅鐮佸叏閮ㄥけ鏁堬紙鐢ㄦ埛閲嶆柊鍒涘缓鍗冲彲锛屼笉褰卞搷宸插缓绔嬬殑浼氳瘽锛夈€?> 杩欐槸鏈夋剰璁捐鈥斺€斾笉闇€瑕佹暟鎹簱锛屼篃涓嶇暀閰嶅鐥曡抗銆?
---

## 涓夈€佹帴鍙ｅ畾涔?
鎵€鏈夊搷搴斿潎涓?`application/json; charset=utf-8`锛屼笖甯?`Cache-Control: no-store`銆?鑻ユ湇鍔＄閰嶇疆浜?`--token`锛?*鎵€鏈変笟鍔℃帴鍙?*閮介渶甯?`Authorization: Bearer <token>`銆?
### `GET /health`

鎺㈡椿 + 缁熻銆?*涓嶉渶瑕佷护鐗?*銆?
```json
{ "ok": true, "version": 1, "uptimeSec": 42, "active": 1, "capacity": 5000, "ttlSec": 180 }
```

### `POST /rendezvous/register`

鐧昏涓€鏉￠厤瀵逛俊鎭紝杩斿洖鐭爜銆?
璇锋眰锛?
```json
{ "ttl": 180, "payload": "{\"sid\":\"...\",\"pk\":\"...\",\"cands\":[...]}" }
```

| 瀛楁 | 璇存槑 |
|---|---|
| `ttl` | 鍙€夈€傝姹傛湁鏁堟湡锛堢锛夛紝鏈嶅姟绔細澶圭揣鍒?`[30, --ttl]` |
| `payload` | 蹇呭～銆傞厤瀵逛俊鎭殑 JSON 鏂囨湰锛堟湇鍔″櫒瑙嗕负**涓嶉€忔槑瀛楃涓?*锛屼笉瑙ｆ瀽锛?|

鍝嶅簲 `200`锛?
```json
{ "code": "5689-ABCN", "expiresIn": 180 }
```

> `code` 鐢?*鏈嶅姟绔潈濞佸垎閰?*锛堝鎴风鑷甫鐨勫€欓€夌爜浼氳蹇界暐锛夛紝淇濊瘉鍞竴鎬с€?
閿欒锛歚400 bad_json/bad_payload`銆乣401 unauthorized`銆乣413 payload_too_large`銆乣429 rate_limited`銆?
### `GET /rendezvous/lookup?code=<鐭爜>`

鐢ㄧ煭鐮佹崲鍙栭厤瀵逛俊鎭紝**鎴愬姛鍗虫秷璐?*锛堝悓鐮佷笉鍙啀娆′娇鐢級銆?
鍝嶅簲 `200`锛?
```json
{ "payload": "{\"sid\":\"...\",\"pk\":\"...\",\"cands\":[...]}" }
```

- 鐭爜杈撳叆瀹藉锛氬拷鐣ヨ繛瀛楃涓庡ぇ灏忓啓锛坄5689abcd` 涓?`5689-ABCN` 绛変环锛?- 鏍煎紡闈炴硶 鈫?`400 bad_code`
- 涓嶅瓨鍦?/ 宸茶繃鏈?/ 宸蹭娇鐢?鈫?**缁熶竴** `404 not_found_or_expired`锛堟晠鎰忎笉鍖哄垎锛岄槻鎺㈡祴锛?- 瓒呴檺 鈫?`429 rate_limited`锛堝甫 `Retry-After: 60`锛?
---

## 鍥涖€佸崌绾у埌 HTTPS锛堟湁鍩熷悕鍚庡己鐑堝缓璁級

浠?Nginx 涓轰緥锛坄certbot` 鐢宠璇佷功锛夛細

```nginx
server {
    listen 443 ssl http2;
    server_name link.example.com;

    ssl_certificate     /etc/letsencrypt/live/link.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/link.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        # 璁╂湇鍔＄鎷垮埌鐪熷疄瀹㈡埛绔?IP锛堥檺娴佹寜鐪熷疄 IP 璁℃暟锛?        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Real-IP $remote_addr;
    }
}

server {
    listen 80;
    server_name link.example.com;
    return 301 https://$host$request_uri;
}
```

瀵瑰簲鍚姩鍙傛暟鍔?`--behind-proxy`锛屽鎴风璁剧疆閲屽崗璁敼涓?`https`銆佺鍙ｅ～ `443`銆?
> 鈿狅笍 `--behind-proxy` 浼氫俊浠?`X-Forwarded-For`銆?*鍙湁鍦ㄤ綘纭疄鍓嶇疆浜嗗彲淇″弽鍚戜唬鐞嗘椂鎵嶅紑鍚?*锛?> 鍚﹀垯鏀诲嚮鑰呭彲浼€犺澶寸粫杩囬檺娴併€?
---

## 浜斻€佽嚜妫€

鏀硅繃鏈嶅姟绔唬鐮佸悗璇峰姟蹇呰窇锛?
```bash
PYTHONIOENCODING=utf-8 python3 server/selftest.py
```

瑕嗙洊 28 椤规柇瑷€锛岄噸鐐规槸 **[2] 涓庡鎴风绠楁硶涓€鑷存€т氦鍙夋瘮瀵?*鈥斺€?鐭爜鐨勫瓧绗﹂泦銆佹牎楠岀畻娉曘€佹爣鍑嗗寲瑙勫垯鍦?Kotlin 渚э紙`RendezvousCodeFormat`锛夊拰 Python 渚?锛坄check_char` / `normalize_code` / `format_code`锛夊悇鏈変竴浠藉疄鐜帮紝
**浠讳綍涓€渚ф敼鍔ㄩ兘蹇呴』璁╀袱渚т繚鎸佷竴鑷?*锛屽惁鍒欎細鍑虹幇銆屽鎴风鐢熸垚鐨勭爜鏈嶅姟绔笉璁ゃ€嶃€?璇ユ祴璇曞氨鏄敤鏉ラ拤浣忚繖涓笉鍙橀噺鐨勩€?
---

## 鍏€佸弬鏁伴€熸煡

| 鍙傛暟 | 榛樿 | 璇存槑 |
|---|---|---|
| `--host` | `0.0.0.0` | 鐩戝惉鍦板潃 |
| `--port` | `8080` | 鐩戝惉绔彛 |
| `--ttl` | `180` | 鐭爜鏈夋晥鏈熶笂闄愶紙绉掞級锛屽悓鏃舵槸榛樿鍊?|
| `--capacity` | `5000` | 娲昏穬璁板綍涓婇檺锛岃秴鍑烘椂鎸ゆ帀鏈€蹇繃鏈熺殑 |
| `--max-payload` | `16384` | 璇锋眰浣撲笂闄愶紙瀛楄妭锛?|
| `--token` | 绌?| Bearer 璁块棶浠ょ墝锛?*鍏綉閮ㄧ讲寤鸿鍚敤** |
| `--lookup-limit` | `20` | 姣?IP 姣忓垎閽熸煡璇笂闄愶紙**涓嶈鏀惧锛岃繖鏄槻鏋氫妇涓诲姏**锛?|
| `--register-limit` | `30` | 姣?IP 姣忓垎閽熺櫥璁颁笂闄?|
| `--behind-proxy` | 鍏?| 淇′换 `X-Forwarded-For` |
| `--quiet` | 鍏?| 鍏抽棴璁块棶鏃ュ織 |
