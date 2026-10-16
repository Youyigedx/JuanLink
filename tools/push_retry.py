#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
网络恢复后自动推送积压提交（直连 git push 优先，失败自动回退 API 通道）。

背景
----
本项目所在网络对 GitHub 的连通性**间歇性**。实测三种状态都出现过：
  - 全部端点可达 → `git push` 直接成功
  - `api.github.com` 可达而 `github.com` 不通 → 需走 Git Data API（tools/git_push_via_api.py）
  - 仅 `ssh.github.com:443` 可达、公钥又未注册 → 两条通道都不可用

另有一个**结构性陷阱**：走 API 通道推送时，GitHub 会重建提交对象，
远端 SHA 与本地不同。若此后本地未 `fetch` 到那个远端对象，
下次 API 推送会因 `Invalid revision range` 失败，而直连 `git push` 又可能被网络挡住。
→ 本脚本检测到该错误时会自动改用 `--api-base origin/<branch>` 重试
（以本地 origin 引用为基准；其树与远端 head 相同，服务端仍判定为快进）。

本脚本按「直连 → fetch 对齐 → API（含 --api-base 兜底）」的顺序自动尝试，
直到成功或超时。

用法
----
    python3 tools/push_retry.py <仓库路径> [--repo owner/name] [--branch main]
                                [--minutes 30] [--interval 30]

退出码：0 已推送（或无积压）；1 超时仍未推送成功。
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
GH = r"C:\Program Files\GitHub CLI\gh.exe"


def run(cmd: list[str], cwd: str | None = None) -> tuple[int, str]:
    p = subprocess.run(cmd, cwd=cwd, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, encoding="utf-8", errors="replace")
    return p.returncode, (p.stdout or "").strip()


def git(repo: str, *args: str) -> tuple[int, str]:
    return run(["git", "-C", repo, *args])


def first_line(s) -> str:
    """取首行；容忍 list（调用方有时传的是筛选后的行列表）。"""
    if not s:
        return ""
    if isinstance(s, list):
        s = s[0] if s else ""
    return (s or "").splitlines()[0] if s else ""


def remote_head(repo: str, repo_full: str, branch: str) -> str | None:
    """经 API 取远端分支 head；不可达返回 None。"""
    if not os.path.exists(GH):
        return None
    code, out = run([GH, "api", f"repos/{repo_full}/git/ref/heads/{branch}"])
    if code != 0:
        return None
    import json
    try:
        return json.loads(out)["object"]["sha"]
    except Exception:
        return None


def pending(repo: str, remote_sha: str | None) -> tuple[str, bool]:
    """返回 (HEAD, 是否有积压)。remote_sha 为 None 时以 origin/<branch> 为参照。"""
    _, head = git(repo, "rev-parse", "HEAD")
    head = head.strip()
    base = remote_sha
    if not base:
        code, out = git(repo, "rev-parse", "origin/main")
        base = out.strip() if code == 0 else None
    if not base:
        return head, True
    code, out = git(repo, "rev-list", "--count", f"{base}..HEAD")
    if code != 0:
        return head, True
    return head, int(out.strip() or 0) > 0


def try_direct(repo: str, branch: str) -> bool:
    code, out = run([
        "git", "-C", repo,
        "-c", "http.lowSpeedLimit=500", "-c", "http.lowSpeedTime=60",
        "push", "origin", branch,
    ])
    if code == 0:
        print("  [直连] 成功")
        return True
    print(f"  [直连] 失败：{first_line(out)[:100]}")
    return False


def try_fetch(repo: str, branch: str) -> bool:
    code, out = run([
        "git", "-C", repo,
        "-c", "http.lowSpeedLimit=500", "-c", "http.lowSpeedTime=60",
        "fetch", "origin", branch,
    ])
    if code == 0:
        return True
    print(f"  [fetch] 失败：{first_line(out)[:100]}")
    return False


def try_api(repo: str, repo_full: str, branch: str) -> bool:
    script = os.path.join(HERE, "git_push_via_api.py")
    if not os.path.exists(script):
        print("  [API] 缺少 tools/git_push_via_api.py")
        return False

    # 先按默认方式（以远端 head SHA 为基准）
    code, out = run([sys.executable, script, repo, repo_full, branch])
    if code == 0:
        line = [l for l in out.splitlines() if "已推送" in l]
        print("  [API] 成功：" + first_line(line))
        return True

    # 兜底：远端 head 对象可能不在本地（此前经 API 推送被 GitHub 重建过，
    # 且 local 又没 fetch 回来），导致 `rev-list <远端SHA>..HEAD` 报
    # Invalid revision range。此时改用本地 origin/<branch> 作基准即可。
    if "Invalid revision range" in out:
        print("  [API] 远端 head 对象本地缺失 → 改用 --api-base origin/%s" % branch)
        code2, out2 = run([
            sys.executable, script, repo, repo_full, branch,
            "--api-base", f"origin/{branch}",
        ])
        if code2 == 0:
            line = [l for l in out2.splitlines() if "已推送" in l]
            print("  [API/--api-base] 成功：" + first_line(line))
            return True
        out = out2

    tail = [l for l in out.splitlines() if l.strip()][-3:]
    print("  [API] 失败：" + " | ".join(x[:90] for x in tail))
    return False


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("repo")
    ap.add_argument("--repo", dest="repo_full", default="Youyigedx/JuanLink")
    ap.add_argument("--branch", default="main")
    ap.add_argument("--minutes", type=float, default=30)
    ap.add_argument("--interval", type=float, default=30)
    a = ap.parse_args(argv)

    repo = os.path.abspath(a.repo)
    deadline = time.time() + a.minutes * 60
    attempt = 0

    while True:
        attempt += 1
        print(f"\n=== 第 {attempt} 轮（剩余 {max(0, int((deadline - time.time()) / 60))} 分钟）===")

        head, has_pending = pending(repo, None)
        if not has_pending:
            print("  无积压提交，远端已是最新。")
            return 0
        print(f"  本地 HEAD = {head[:12]}，存在未推送提交")

        # 1) 直连最干净：保留真实提交 SHA，且不需要远端对象
        if try_direct(repo, a.branch):
            return 0

        # 2) 取回远端对象 —— 这是 API 通道能工作的前提
        #    （API 推送会重建提交对象，若不 fetch 回来，下次范围算不出来）
        fetched = try_fetch(repo, a.branch)
        if fetched:
            _, has_pending = pending(repo, None)
            if not has_pending:
                print("  fetch 后确认远端已包含全部提交。")
                return 0
            # fetch 成功 → 建议先对齐到远端再 API 推送，避免算出分叉范围
            rsha = remote_head(repo, a.repo_full, a.branch)
            if rsha:
                code, _ = git(repo, "merge-base", "--is-ancestor", rsha, "HEAD")
                if code == 0:
                    print(f"  本地已包含远端 {rsha[:12]}，可直接 API 推送")
                else:
                    print(f"  本地与远端 {rsha[:12]} 分叉：如需对齐请手动 git reset --hard {rsha[:12]}")

        # 3) API 通道
        if try_api(repo, a.repo_full, a.branch):
            return 0

        if time.time() >= deadline:
            print("\n超时：仍未推送成功。")
            print("提示：")
            print("  - 若你有代理/VPN，启动后直接重跑本脚本即可（直连会自动成功）。")
            print("  - 也可先把 SSH 公钥注册到 GitHub，走 ssh.github.com:443：")
            print("      ~/.ssh/config 中配置 Host github.com -> HostName ssh.github.com, Port 443")
            return 1

        print(f"  {a.interval:.0f}s 后重试…")
        time.sleep(a.interval)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
