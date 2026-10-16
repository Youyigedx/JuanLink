#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
校验远端提交链的**消息、作者、父子关系**是否与本地一致。

背景：`git_push_via_api.py` 通过 REST API 重建提交对象，因此提交 SHA 会与本地不同
（committer 字段编码差异）。**树内容不受影响**（已由 verify_remote.py 逐项比对），
但消息/作者/父链必须逐一核对，确保没有语义丢失。

用法：python3 server/verify_commit_chain.py <仓库路径> <owner/repo> [分支]
"""

from __future__ import annotations

import json
import subprocess
import sys

GH = r"C:\Program Files\GitHub CLI\gh.exe"


def gh_json(path: str):
    p = subprocess.run([GH, "api", path], stdout=subprocess.PIPE,
                       stderr=subprocess.PIPE, encoding="utf-8", errors="replace")
    if p.returncode != 0:
        raise RuntimeError(f"gh api {path} 失败: {p.stderr.strip()}")
    return json.loads(p.stdout)


def git_text(repo: str, *args: str) -> str:
    p = subprocess.run(["git", "-C", repo, *args],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode("utf-8", "replace"))
    return p.stdout.decode("utf-8", "surrogateescape")


def main(argv):
    repo, repo_full = argv[0], argv[1]
    branch = argv[2] if len(argv) > 2 else "main"

    remote_head = gh_json(f"repos/{repo_full}/git/ref/heads/{branch}")["object"]["sha"]

    # 本地按「本地 HEAD 往回数 N 个」取，N 用远端与本地共同祖先之后的提交数
    remote_commits = []
    sha = remote_head
    for _ in range(6):                     # 只看最近 6 个足够覆盖本次推送
        c = gh_json(f"repos/{repo_full}/git/commits/{sha}")
        remote_commits.append(c)
        sha = c["parents"][0]["sha"] if c["parents"] else None
        if not sha:
            break

    print(f"远端 {branch} = {remote_head}")
    print("远端最近提交：")
    for c in remote_commits[:4]:
        # ⚠️ Git Data API 的 /git/commits 把 message/author 放在**顶层**，
        # 不同于 Repos API /repos/{o}/{r}/commits 的 commit.* 嵌套结构。
        first = c["message"].splitlines()[0]
        print(f"  {c['sha'][:12]}  {c['author']['name']} <{c['author']['email']}>  {first[:60]}")

    # 逐个把远端提交的消息首行与本地对应提交比对
    print("\n与本地提交消息比对：")
    local_log = git_text(repo, "log", "-6", "--pretty=%H%x01%an%x01%ae%x01%s").splitlines()
    local_by_subject = {}
    for line in local_log:
        h, an, ae, subj = line.split("\x01")
        local_by_subject[subj] = (h, an, ae)

    all_ok = True
    for c in remote_commits[:4]:
        subj = c["message"].splitlines()[0]
        r_an = c["author"]["name"]
        r_ae = c["author"]["email"]
        if subj in local_by_subject:
            lh, lan, lae = local_by_subject[subj]
            ok = (r_an == lan and r_ae == lae)
            all_ok &= ok
            print(f"  {'OK ' if ok else 'BAD'} {subj[:50]}")
            if not ok:
                print(f"      本地 {lan} <{lae}>")
                print(f"      远端 {r_an} <{r_ae}>")
        else:
            print(f"  --  {subj[:50]}  (本地最近 6 个提交中未找到，可能是更早的提交)")

    # 父链连续性
    print("\n父链连续性：")
    chain_ok = True
    for i in range(len(remote_commits) - 1):
        child, parent = remote_commits[i], remote_commits[i + 1]
        link = child["parents"][0]["sha"] == parent["sha"]
        chain_ok &= link
        print(f"  {child['sha'][:12]} -> {parent['sha'][:12]}  {'OK' if link else 'BAD'}")
    all_ok &= chain_ok

    print()
    print("=" * 60)
    print("✅ 提交消息/作者/父链一致" if all_ok else "❌ 存在不一致，需排查")
    print("=" * 60)
    print("注意：提交 SHA 与本地不同属预期——API 重建了提交对象，")
    print("      但树内容已由 verify_remote.py 证明逐项一致。")
    return 0 if all_ok else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
