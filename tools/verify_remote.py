#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
校验刚刚推送到 GitHub 的内容是否与本地 git 提交**逐字节一致**。

为什么必须单独校验
------------------
`git_push_via_api.py` 走的是 REST API 而非 git 协议，成功返回不代表内容正确：
编码、base64、树构造任一环节出错都可能推上"看似成功但损坏"的内容。
本脚本把远端 main 的整棵树拉下来，与本地 HEAD 的树逐项比对 SHA。

用法：python3 server/verify_remote.py <仓库路径> <owner/repo>
"""

from __future__ import annotations

import json
import subprocess
import sys

GH = r"C:\Program Files\GitHub CLI\gh.exe"


def gh_json(path: str):
    p = subprocess.run(
        [GH, "api", path],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        encoding="utf-8", errors="surrogateescape",
    )
    if p.returncode != 0:
        raise RuntimeError(f"gh api {path} 失败: {p.stderr.strip()}")
    return json.loads(p.stdout)


def git_text(repo: str, *args: str) -> str:
    p = subprocess.run(["git", "-C", repo, *args],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode("utf-8", "replace"))
    return p.stdout.decode("utf-8", "surrogateescape")


def local_tree_flat(repo: str, tree: str) -> dict[str, str]:
    """本地某个树的全量扁平清单：path -> sha（含 mode 前缀便于比对类型）。"""
    raw = subprocess.run(["git", "-C", repo, "ls-tree", "-r", "-z", tree],
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout
    out = {}
    for rec in raw.split(b"\x00"):
        if not rec:
            continue
        meta, path = rec.split(b"\t", 1)
        mode, _typ, sha = meta.decode().split(" ")
        out[path.decode("utf-8", "surrogateescape")] = f"{mode}:{sha}"
    return out


def remote_tree_flat(repo_full: str, tree_sha: str) -> dict[str, str]:
    """远端某个树的全量扁平清单（API 递归）。"""
    data = gh_json(f"repos/{repo_full}/git/trees/{tree_sha}?recursive=1")
    if data.get("truncated"):
        raise RuntimeError("远端树响应被截断，无法完整校验")
    out = {}
    for e in data.get("tree", []):
        if e["type"] == "tree":
            continue          # 只比文件
        out[e["path"]] = f"{e['mode']}:{e['sha']}"
    return out


def main(argv):
    repo, repo_full = argv[0], argv[1]
    local_head = git_text(repo, "rev-parse", "HEAD").strip()
    local_tree = git_text(repo, "rev-parse", "HEAD^{tree}").strip()

    remote_head = gh_json(f"repos/{repo_full}/git/ref/heads/main")["object"]["sha"]
    remote_tree = gh_json(f"repos/{repo_full}/git/commits/{remote_head}")["tree"]["sha"]

    print(f"本地 HEAD        = {local_head}")
    print(f"远端 main        = {remote_head}")
    print(f"提交一致         = {local_head == remote_head}")
    print(f"本地 tree        = {local_tree}")
    print(f"远端 tree        = {remote_tree}")
    print()

    lt = local_tree_flat(repo, local_tree)
    rt = remote_tree_flat(repo_full, remote_tree)

    print(f"本地文件数 = {len(lt)}   远端文件数 = {len(rt)}")

    only_local = sorted(set(lt) - set(rt))
    only_remote = sorted(set(rt) - set(lt))
    diff = sorted(p for p in (set(lt) & set(rt)) if lt[p] != rt[p])

    print(f"仅本地存在 = {len(only_local)}")
    for p in only_local[:20]:
        print(f"   - {p}")
    print(f"仅远端存在 = {len(only_remote)}")
    for p in only_remote[:20]:
        print(f"   + {p}")
    print(f"内容/类型不一致 = {len(diff)}")
    for p in diff[:20]:
        print(f"   ! {p}\n       本地 {lt[p]}\n       远端 {rt[p]}")

    ok = (local_head == remote_head and not only_local and not only_remote and not diff)
    print()
    print("=" * 60)
    print("✅ 远端与本地完全一致" if ok else "❌ 存在差异，需排查")
    print("=" * 60)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
