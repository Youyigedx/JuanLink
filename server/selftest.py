#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Juan LinK 会合服务器 —— 自检脚本（零依赖，直接 `python3 server/selftest.py`）

覆盖：
  1. 短码格式与校验位的纯函数性质（含与客户端算法一致性）
  2. HTTP 端到端：登记 → 取回 → 一次性消费 → 限流 → 畸形输入
  3. 服务端短码校验与客户端 RendezvousCodeFormat 的**交叉比对**
     （用 Kotlin 侧算法在此重实现，逐字符比对，防止两端漂移）

退出码：0 全通过；1 有失败。
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import rendezvous_server as rv  # noqa: E402

PASS = 0
FAIL = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name}" + (f"  [{detail}]" if detail else ""))


# ---------------------------------------------------------------- 纯函数性质

def test_code_format() -> None:
    print("\n[1] 短码格式与校验位")
    codes = [rv.generate_code(set()) for _ in range(300)]
    check("全部通过严格校验", all(rv.validate_code(c) for c in codes))
    check("全部为 DDDD-LLLL 展示形态", all(rv.format_code(c) and len(c) == 8 for c in codes))
    check("前 4 位为数字", all(c[:4].isdigit() for c in codes))
    check("第 5-7 位为字母", all(not ch.isdigit() for c in codes for ch in c[4:7]))
    check("校验位为字母（防 O/0 混淆）", all(not c[7].isdigit() for c in codes))
    check("校验位计算正确", all(rv.check_char(c[:7]) == c[7] for c in codes))
    check("300 个码无重复", len(set(codes)) == 300)

    # 校验位对单字符篡改敏感
    bad = 0
    for c in codes[:100]:
        t = c[:7] + ("A" if c[7] != "A" else "B")
        if not rv.validate_code(t):
            bad += 1
    check("单字符篡改全部被拒", bad == 100, f"仅 {bad}/100 被拒")

    check("长度错误被拒", not rv.validate_code("123") and not rv.validate_code("1234567890"))
    check("前 4 位含字母被拒", not rv.validate_code("A234-BCDE"))
    check("非法字符被拒", not rv.validate_code("1234-BCD!"))


def test_client_parity() -> None:
    """
    与客户端算法交叉比对。

    Kotlin 侧 RendezvousCodeFormat：CHARSET 相同、checkChar = Σ(v_i × (i+1)) mod 32。
    此处对全部 32^2 组合抽样的 (body7, check) 映射做一致性断言。
    """
    print("\n[2] 与客户端算法一致性（交叉比对）")
    # 重新实现一遍客户端逻辑，但用独立写法，避免与生产实现共用同一份错
    cs = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    mismatches = 0
    tested = 0
    for d1 in "0123456789":
        for d2 in "0123456789":
            for a in cs[10:20]:
                body = f"1{d1}2{d2}{a}AB"[:7]
                if len(body) != 7:
                    continue
                expected = cs[sum(cs.index(body[i]) * (i + 1) for i in range(7)) % 32]
                if rv.check_char(body) != expected:
                    mismatches += 1
                tested += 1
    check(f"加权校验算法一致（{tested} 组抽样）", mismatches == 0, f"{mismatches} 组不一致")

    check("CHARSET 与客户端一致", rv.CHARSET == cs)
    # normalize 只做「去分隔符 + 转大写」，**不计算校验位**，所以这里期望 5689ABCD。
    # 注意：这正是当初 5689-ABCD 被写进文档的原因——normalize 的结果看起来像个合法码。
    check("normalize 与客户端一致", rv.normalize_code(" 5689 abcd ") == "5689ABCD")
    check("format 与客户端 UI 一致", rv.format_code("5689ABCN") == "5689-ABCN")
    # 回归钉子：文档/UI 共用的样例码必须是合法码。
    # 曾经 `5689-ABCD` 的校验位是错的（正确校验位 'N'），用户照抄 UI 提示会被判校验失败。
    check("样例码 5689-ABCN 合法", rv.validate_code("5689-ABCN"))
    check("样例码校验位为 N", rv.check_char("5689ABC") == "N")
    check("5689-ABCD 确实是非法码（防止改回错误样例）", not rv.validate_code("5689-ABCD"))


# ---------------------------------------------------------------- HTTP 端到端

def test_http() -> None:
    print("\n[3] HTTP 端到端")
    port = 18190
    # lookup-limit 给足，避免「畸形码用例」把额度吃掉导致后续用例误判 429；
    # 限流本身由末尾的专项用例放大额度来验证。
    proc = subprocess.Popen(
        [sys.executable, os.path.join(HERE, "rendezvous_server.py"),
         "--port", str(port), "--ttl", "60", "--lookup-limit", "100", "--quiet"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    base = f"http://127.0.0.1:{port}"
    try:
        # 等服务端起来
        up = False
        for _ in range(50):
            try:
                with urllib.request.urlopen(base + "/health", timeout=0.5) as r:
                    health = json.loads(r.read().decode())
                up = True
                break
            except Exception:
                time.sleep(0.1)
        check("服务器启动成功", up)
        if not up:
            return
        check("health 上报记录上限", health.get("capacity", 0) > 0)

        def post(path, obj):
            data = json.dumps(obj).encode()
            req = urllib.request.Request(base + path, data=data,
                                         headers={"Content-Type": "application/json"})
            return urllib.request.urlopen(req, timeout=3)

        def get(path):
            return urllib.request.urlopen(base + path, timeout=3)

        payload = '{"sid":"s1","pk":"cHVia2V5"}'
        with post("/rendezvous/register", {"ttl": 60, "payload": payload}) as r:
            reg = json.loads(r.read().decode())
        code = reg["code"]
        check("登记返回短码", bool(code))
        check("返回 TTL 被夹紧到服务端上限", reg["expiresIn"] <= 60)

        with get(f"/rendezvous/lookup?code={code}") as r:
            got = json.loads(r.read().decode())
        check("取回 payload 一致", got["payload"] == payload)

        # 一次性消费
        try:
            get(f"/rendezvous/lookup?code={code}")
            check("二次查询被拒", False)
        except urllib.error.HTTPError as e:
            check("二次查询被拒 (404)", e.code == 404)

        # 宽容输入
        with post("/rendezvous/register", {"payload": payload}) as r:
            code2 = json.loads(r.read().decode())["code"]
        with get(f"/rendezvous/lookup?code={code2.replace('-','').lower()}") as r:
            got2 = json.loads(r.read().decode())
        check("无连字符+小写可查", got2["payload"] == payload)

        # 畸形码
        for bad in ("AAAA-AAAA", "123", "ABCD-EFGH", "1234-BCD!"):
            try:
                get(f"/rendezvous/lookup?code={bad}")
                check(f"畸形码 {bad} 被拒", False)
            except urllib.error.HTTPError as e:
                check(f"畸形码 {bad} -> {e.code}", e.code == 400)

        # 空 payload 被拒
        try:
            with post("/rendezvous/register", {"payload": "   "}) as r:
                r.read()
            check("空 payload 被拒", False)
        except urllib.error.HTTPError as e:
            check(f"空 payload -> {e.code}", e.code == 400)

        # 限流：单独起一台低配额实例，避免与其它用例互相干扰
        rl_ok = test_rate_limit()
        check("查询限流生效 (429)", rl_ok)
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()


def test_rate_limit() -> bool:
    """独立实例验证限流：lookup-limit=3，第 4 次查询必须 429。"""
    port = 18191
    proc = subprocess.Popen(
        [sys.executable, os.path.join(HERE, "rendezvous_server.py"),
         "--port", str(port), "--lookup-limit", "3", "--register-limit", "3", "--quiet"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    base = f"http://127.0.0.1:{port}"
    try:
        for _ in range(50):
            try:
                with urllib.request.urlopen(base + "/health", timeout=0.5):
                    break
            except Exception:
                time.sleep(0.1)
        codes = []
        for _ in range(6):
            try:
                with urllib.request.urlopen(base + "/rendezvous/lookup?code=0000-AAAA", timeout=2) as r:
                    codes.append(r.status)
            except urllib.error.HTTPError as e:
                codes.append(e.code)
        # 前 3 次 400（格式合法但不存在），之后必须出现 429
        return 429 in codes
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()


def main() -> int:
    test_code_format()
    test_client_parity()
    test_http()
    print(f"\n================ PASS={PASS}  FAIL={FAIL} ================")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
