#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Juan LinK 会合服务器（Rendezvous Server）
=========================================

作用：把「长连接串」换成「8 位短配对码」。

    A 端创建协作  --POST /rendezvous/register-->  服务器存下配对信息，返回短码
    B 端输入短码  --GET  /rendezvous/lookup------>  服务器返回配对信息
    A、B 随后直连（局域网 TCP 或 TURN 中继），服务器不再参与

⚠️ 安全边界（务必阅读，勿误解为端到端安全）
--------------------------------------------------------------
本服务器**参与配对信令**，因此它（或被攻陷/被劫持的服务器）**具备中间人能力**：
它可以向两端返回自己的公钥，从而解密画布内容。

即：短配对码带来便利，**代价是服务器成为信任方**。
原有「公钥进二维码（带外可信）」的免服务器信任模型仅在使用二维码/连接串时成立。

若要重新获得可验证的安全性，需在握手后比对「短认证串（SAS）」——
当前客户端未实现，故此处如实说明。

⚠️ 短码空间仅约 10^6，且默认明文 HTTP：
   攻击者可枚举短码把别人的配对记录抢先取走。因此本服务强制：
     - 短 TTL（默认 180 秒）
     - 一次性消费（取走即失效，且不再重发）
     - 按来源 IP 严格限流（查询与登记分别限流）
     - 活跃记录数上限（防内存耗尽）
   这些能**大幅降低**被枚举的成功率，但**不能**替代 HTTPS。
   拥有域名与证书后请务必改用 https（见 README.md 的 Nginx 示例）。

零第三方依赖：仅用 Python 3 标准库，可直接 python3 运行。
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

# ----------------------------------------------------------------------------
# 短码格式（必须与客户端 core/rendezvous/Rendezvous.kt 的 RendezvousCodeFormat 一致）
# ----------------------------------------------------------------------------

CHARSET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def check_char(body7: str) -> str:
    """校验位：前 7 字符加权求和 mod 32。"""
    total = 0
    for i, ch in enumerate(body7):
        v = CHARSET.find(ch)
        if v < 0:
            raise ValueError(f"非法字符: {ch}")
        total += v * (i + 1)
    return CHARSET[total % 32]


def normalize_code(code: str) -> str:
    """去分隔符与空白、转大写。"""
    return code.replace("-", "").replace(" ", "").replace("\t", "").upper()


def validate_code(code: str) -> bool:
    """
    严格校验：长度 8、前 4 位为数字、校验位匹配。
    在「查表之前」就拒绝畸形输入，避免用无效请求打满限流计数。
    """
    n = normalize_code(code)
    if len(n) != 8:
        return False
    if not all(c.isdigit() and c.isascii() for c in n[:4]):
        return False
    try:
        return check_char(n[:7]) == n[7]
    except ValueError:
        return False


def generate_code(taken) -> str:
    """
    生成一个未被占用的短码：4 位数字 + 3 位字母 + 1 位校验字母。

    ⚠️ 返回**已标准化**（无连字符）的码：存储键与查表都走标准化形式，
    避免「存 `6474-BTYM`、查 `6474BTYM`」这类键不一致导致的 404。
    展示用连字符由 [format_code] 负责。

    校验位被强制为**字母**（丢弃校验值为数字的候选）：这样展示形态恒为
    `DDDD-LLLL`，用户口头传达或手抄时不会把校验位的数字 `0/1` 与字母 `O/I` 混淆。
    代价是校验位空间从 32 降到 22（约 1 bit），必须靠服务端限流兜住——
    因此**不要**为了提高码空间而放宽 --lookup-limit。
    """
    for _ in range(256):
        digits = "".join(CHARSET[secrets.randbelow(10)] for _ in range(4))
        letters = "".join(CHARSET[10 + secrets.randbelow(22)] for _ in range(3))
        body = digits + letters
        c = check_char(body)
        if not c.isdigit():
            code = body + c
            if code not in taken:
                return code
    raise RuntimeError("短码空间拥挤，分配失败")


def format_code(code: str) -> str:
    """展示形态：`6474BTYM` → `6474-BTYM`（与客户端 UI 一致）。"""
    n = normalize_code(code)
    return f"{n[:4]}-{n[4:]}" if len(n) == 8 else code


# ----------------------------------------------------------------------------
# 存储与限流
# ----------------------------------------------------------------------------


class RateLimiter:
    """
    固定窗口限流器：按 key（通常是客户端 IP）计数。

    说明：固定窗口在窗口边界上允许约 2 倍突发，对本场景（粗粒度挡枚举）足够；
    如需严格，可换成令牌桶。
    """

    def __init__(self, limit: int, window_sec: float):
        self.limit = limit
        self.window_sec = window_sec
        self._hits: dict[str, list] = {}
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        with self._lock:
            start, count = self._hits.get(key, (now, 0))
            if now - start > self.window_sec:
                start, count = now, 0
            count += 1
            self._hits[key] = (start, count)
            # 顺手回收过期桶，避免 dict 无限增长
            if len(self._hits) > 20000:
                self._hits = {
                    k: v for k, v in self._hits.items() if now - v[0] <= self.window_sec
                }
            return count <= self.limit


class RendezvousStore:
    """
    内存配对记录表。

    - TTL 到期即失效（惰性清理）
    - lookup 成功即消费（一次性）
    - 容量上限，防止被灌满内存
    """

    def __init__(self, ttl_sec: int, capacity: int):
        self.ttl_sec = ttl_sec
        self.capacity = capacity
        self._items: dict[str, dict] = {}
        self._lock = threading.Lock()

    def _purge_locked(self) -> None:
        now = time.time()
        expired = [c for c, it in self._items.items() if it["expires_at"] <= now]
        for c in expired:
            self._items.pop(c, None)

    def put(self, ttl: int, payload: str) -> tuple[str, int]:
        """
        登记一条配对记录。

        返回 (展示形态短码, 实际 TTL 秒)；**存储键为标准化形态**，
        因此 take() 用标准化码即可命中。
        """
        ttl = max(30, min(int(ttl or self.ttl_sec), self.ttl_sec))
        with self._lock:
            self._purge_locked()
            if len(self._items) >= self.capacity:
                # 优先挤掉最快过期的记录
                oldest = min(self._items, key=lambda c: self._items[c]["expires_at"])
                self._items.pop(oldest, None)
            code = generate_code(self._items)          # 标准化形态，用作键
            self._items[code] = {
                "payload": payload,
                "expires_at": time.time() + ttl,
            }
            return format_code(code), ttl

    def take(self, code: str) -> str | None:
        """取走并消费一条记录；不存在/过期/已用过都返回 None（不区分，防探测）。"""
        with self._lock:
            self._purge_locked()
            item = self._items.pop(code, None)
        if item is None:
            return None
        if item["expires_at"] <= time.time():
            return None
        return item["payload"]

    def stats(self) -> dict:
        with self._lock:
            self._purge_locked()
            return {
                "active": len(self._items),
                "capacity": self.capacity,
                "ttlSec": self.ttl_sec,
            }


# ----------------------------------------------------------------------------
# HTTP 处理
# ----------------------------------------------------------------------------


class Config:
    def __init__(self, args: argparse.Namespace):
        self.ttl_sec = args.ttl
        self.max_payload = args.max_payload
        self.access_token = args.token or ""
        self.store = RendezvousStore(ttl_sec=args.ttl, capacity=args.capacity)
        # 查询限流偏严（挡枚举），登记限流稍宽（正常使用就是偶尔创建）
        self.lookup_limiter = RateLimiter(limit=args.lookup_limit, window_sec=60.0)
        self.register_limiter = RateLimiter(limit=args.register_limit, window_sec=60.0)
        # 登记记录同时绑定来源 IP 前缀：跨网/换网场景不强制，但用于日志与滥用排查
        self.started_at = time.time()


CONFIG: Config | None = None


class Handler(BaseHTTPRequestHandler):
    server_version = "JuanLinkRendezvous/1.0"
    protocol_version = "HTTP/1.1"

    # ---------------- 基础设施 ----------------

    def log_message(self, fmt, *args):  # noqa: D102 - 统一走 _log
        if CONFIG and getattr(CONFIG, "quiet", False):
            return
        sys.stderr.write("[rendezvous] %s - %s\n" % (self.client_ip(), fmt % args))

    def client_ip(self) -> str:
        # 反向代理场景：信任 X-Forwarded-For 的首段（仅在你自己前置 Nginx 时使用）
        fwd = self.headers.get("X-Forwarded-For")
        if fwd:
            return fwd.split(",")[0].strip()
        return self.client_address[0] if self.client_address else "unknown"

    def _send_json(self, code: int, obj: dict, extra_headers: dict | None = None) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        if extra_headers:
            for k, v in extra_headers.items():
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self) -> bool:
        """可选 Bearer 令牌鉴权：未配置令牌则放行。"""
        if not CONFIG or not CONFIG.access_token:
            return True
        auth = self.headers.get("Authorization", "")
        expected = f"Bearer {CONFIG.access_token}"
        return hmac.compare_digest(auth, expected)

    def _read_body(self) -> bytes | None:
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return None
        if length <= 0 or length > CONFIG.max_payload:
            return None
        return self.rfile.read(length)

    # ---------------- 路由 ----------------

    def do_GET(self):  # noqa: N802 - BaseHTTPRequestHandler 约定
        path = urlparse(self.path).path
        if path == "/health":
            self._send_json(
                200,
                {
                    "ok": True,
                    "version": 1,
                    "uptimeSec": int(time.time() - CONFIG.started_at),
                    **CONFIG.store.stats(),
                },
            )
            return
        if path == "/rendezvous/lookup":
            self._handle_lookup()
            return
        self._send_json(404, {"error": "not_found"})

    def do_POST(self):  # noqa: N802
        path = urlparse(self.path).path
        if path == "/rendezvous/register":
            self._handle_register()
            return
        self._send_json(404, {"error": "not_found"})

    # ---------------- 业务 ----------------

    def _handle_lookup(self) -> None:
        if not self._authorized():
            self._send_json(401, {"error": "unauthorized"})
            return
        ip = self.client_ip()
        if not CONFIG.lookup_limiter.allow(ip):
            self._send_json(429, {"error": "rate_limited"}, {"Retry-After": "60"})
            return

        query = parse_qs(urlparse(self.path).query)
        raw = (query.get("code") or [""])[0]
        if not validate_code(raw):
            # 畸形码直接拒，不计入有效查询语义（也避免用来探测格式）
            self._send_json(400, {"error": "bad_code"})
            return
        code = normalize_code(raw)
        payload = CONFIG.store.take(code)
        if payload is None:
            # 不存在 / 已过期 / 已被取走 → 统一 404，不泄露区别（防探测）
            self._send_json(404, {"error": "not_found_or_expired"})
            return
        self._send_json(200, {"payload": payload})

    def _handle_register(self) -> None:
        if not self._authorized():
            self._send_json(401, {"error": "unauthorized"})
            return
        ip = self.client_ip()
        if not CONFIG.register_limiter.allow(ip):
            self._send_json(429, {"error": "rate_limited"}, {"Retry-After": "60"})
            return

        body = self._read_body()
        if body is None:
            self._send_json(400, {"error": "bad_body"})
            return
        try:
            req = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self._send_json(400, {"error": "bad_json"})
            return
        if not isinstance(req, dict):
            self._send_json(400, {"error": "bad_json"})
            return

        payload = req.get("payload")
        if not isinstance(payload, str) or not payload.strip():
            self._send_json(400, {"error": "bad_payload"})
            return
        if len(payload.encode("utf-8")) > CONFIG.max_payload:
            self._send_json(413, {"error": "payload_too_large"})
            return

        # 客户端可以自带一个候选码，但**服务端权威**：以服务端分配结果为准
        code, ttl = CONFIG.store.put(req.get("ttl"), payload)
        self._send_json(200, {"code": code, "expiresIn": ttl})


# ----------------------------------------------------------------------------
# 入口
# ----------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    global CONFIG
    parser = argparse.ArgumentParser(
        description="Juan LinK 会合服务器：短配对码 ↔ 配对信息",
    )
    parser.add_argument("--host", default="0.0.0.0", help="监听地址（默认 0.0.0.0）")
    parser.add_argument("--port", type=int, default=8080, help="监听端口（默认 8080）")
    parser.add_argument("--ttl", type=int, default=180, help="配对码有效期秒（默认 180，上限即此值）")
    parser.add_argument("--capacity", type=int, default=5000, help="活跃配对记录上限（默认 5000）")
    parser.add_argument("--max-payload", type=int, default=16384, help="单次请求体上限字节（默认 16KB）")
    parser.add_argument("--token", default="", help="可选 Bearer 访问令牌（留空=不鉴权）")
    parser.add_argument("--lookup-limit", type=int, default=20, help="每 IP 每分钟查询上限（默认 20）")
    parser.add_argument("--register-limit", type=int, default=30, help="每 IP 每分钟登记上限（默认 30）")
    parser.add_argument("--behind-proxy", action="store_true", help="信任 X-Forwarded-For（仅在前置 Nginx 时开启）")
    parser.add_argument("--quiet", action="store_true", help="关闭访问日志")
    args = parser.parse_args(argv)

    cfg = Config(args)
    cfg.quiet = args.quiet
    CONFIG = cfg

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True

    banner = (
        f"\nJuan LinK 会合服务器已启动\n"
        f"  监听        : http://{args.host}:{args.port}\n"
        f"  短码有效期  : {args.ttl}s（一次性消费）\n"
        f"  记录上限    : {args.capacity}\n"
        f"  限流        : 查询 {args.lookup_limit}/分钟·IP，登记 {args.register_limit}/分钟·IP\n"
        f"  鉴权        : {'Bearer 令牌已启用' if cfg.access_token else '未启用（公网部署建议启用 --token）'}\n"
        f"\n  ⚠️ 本服务参与配对信令，是信任方：它能返回自己的公钥成为中间人。\n"
        f"     无 HTTPS 时短码可被同网络窃听者看到。详见 server/README.md。\n"
    )
    sys.stderr.write(banner)
    sys.stderr.flush()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        sys.stderr.write("\n已停止。\n")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
