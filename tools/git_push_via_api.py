#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
通过 GitHub Git Data API 推送本地提交（在 github.com 被网络阻断时使用）。

背景
----
本机 `github.com:443` 的 git 协议被重置（连接被重置 / 超时），
但 `api.github.com` 可达，且 `gh` CLI 已用具备 repo 权限的 token 登录。
因此改用 REST 的 Git Data API（blobs / trees / commits / ref）完成推送。

为什么不直接 `git push`
-----------------------
git 的 smart-HTTP 端点只在 github.com 上，api.github.com 不提供该端点
（实测返回 403/404）。这是网络层限制，不是配置问题。

正确性保证（关键）
------------------
每个对象上传前都重新计算 git 对象哈希并与之比对：
    sha1("blob <len>\\0" + content) == 本地 blob sha
不一致立即中止，绝不写入远端。这样即使 base64 或编码环节出错，
也不可能把损坏的内容推上去。

用法
----
    python3 server/git_push_via_api.py <仓库路径> <owner/repo> <远端分支>
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import subprocess
import sys
import tempfile

GH = r"C:\Program Files\GitHub CLI\gh.exe"


def git(repo: str, *args: str) -> bytes:
    """执行 git 命令，返回原始 stdout 字节（不经过文本层，避免 CRLF/编码破坏）。"""
    p = subprocess.run(
        ["git", "-C", repo, *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if p.returncode != 0:
        raise RuntimeError(
            f"git {' '.join(args)} 失败: {p.stderr.decode('utf-8', 'replace')}"
        )
    return p.stdout


def git_text(repo: str, *args: str) -> str:
    return git(repo, *args).decode("utf-8", "surrogateescape")


def obj_hash(kind: str, content: bytes) -> str:
    """git 对象哈希：sha1("<kind> <len>\\0" + content)"""
    header = f"{kind} {len(content)}".encode()
    return hashlib.sha1(header + b"\x00" + content).hexdigest()


GITMODE_DIR = "40000"


def is_dir(mode: str) -> bool:
    """
    判定是否为目录（子树）。

    ⚠️ 绝不能用 `mode.endswith("4")`：文件模式 `100644`/`100755` 也以 4/5 结尾，
    那样会把 blob 当树递归，报 "fatal: not a tree object"。
    必须按 git 的精确模式判断：目录是 40000（API 返回时省略前导 0）。
    """
    return mode.lstrip("0") == GITMODE_DIR.lstrip("0")


def ls_tree_raw(repo: str, tree_sha: str) -> dict[str, tuple[str, str]]:
    """列出**本地**树的直接子项：name -> (mode, sha)。"""
    raw = git(repo, "ls-tree", "-z", tree_sha)
    return _parse_tree(raw)


def _parse_tree(raw: bytes) -> dict[str, tuple[str, str]]:
    out: dict[str, tuple[str, str]] = {}
    for rec in raw.split(b"\x00"):
        if not rec:
            continue
        meta, name = rec.split(b"\t", 1)
        mode, _typ, sha = meta.decode().split(" ")
        # name 用 surrogateescape 解码，保证中文文件名往返无损
        out[name.decode("utf-8", "surrogateescape")] = (mode, sha)
    return out


def read_blob(repo: str, sha: str) -> bytes:
    return git(repo, "cat-file", "blob", sha)


class Api:
    """gh api 包装：用临时文件传 body，避开命令行长度限制与引号问题。"""

    def __init__(self, repo_full: str):
        self.repo = repo_full
        self.calls = 0

    def post(self, path: str, payload: dict) -> dict:
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False)
            tmp = f.name
        try:
            p = subprocess.run(
                [GH, "api", "-X", "POST", f"repos/{self.repo}/{path}", "--input", tmp],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env={**os.environ, "GH_PAGER": ""},
                encoding="utf-8",
                errors="replace",
            )
            self.calls += 1
            if p.returncode != 0:
                raise RuntimeError(f"API {path} 失败: {p.stderr.strip()}")
            return json.loads(p.stdout) if p.stdout.strip() else {}
        finally:
            os.unlink(tmp)


class Pusher:
    def __init__(self, local_repo: str, remote_repo: str):
        self.repo = local_repo
        self.api = Api(remote_repo)
        self.uploaded = 0
        self.reused = 0
        # 远端树的条目缓存：远端对象本地往往不存在，必须经 API 读取
        self._remote_trees: dict[str, dict[str, tuple[str, str]]] = {}

    def remote_tree_entries(self, tree_sha: str) -> dict[str, tuple[str, str, str]]:
        """
        读取**远端**树的直接子项：name -> (mode, sha, type)。

        ⚠️ 不能用 `git ls-tree`：远端父树里的对象（例如本地分叉点的兄弟子树）
        本地对象库并不存在，会报 "not a tree object"。
        type 直接取 API 返回的字段，比从 mode 推断更可靠。
        """
        if tree_sha in self._remote_trees:
            return self._remote_trees[tree_sha]
        raw = subprocess.run(
            [GH, "api", f"repos/{self.api.repo}/git/trees/{tree_sha}"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            encoding="utf-8", errors="surrogateescape",
        )
        if raw.returncode != 0:
            raise RuntimeError(f"读取远端树失败 {tree_sha}: {raw.stderr.strip()}")
        data = json.loads(raw.stdout)
        out: dict[str, tuple[str, str, str]] = {}
        for e in data.get("tree", []):
            out[e["path"]] = (e["mode"], e["sha"], e["type"])
        self._remote_trees[tree_sha] = out
        return out

    def ensure_blob(self, sha: str) -> str:
        """上传 blob；已存在（远端 409/422）则视为已存在。"""
        content = read_blob(self.repo, sha)
        actual = obj_hash("blob", content)
        if actual != sha:
            raise RuntimeError(
                f"blob 内容与 SHA 不符，中止：期望 {sha}，实算 {actual}"
            )
        try:
            self.api.post("git/blobs", {
                "content": base64.b64encode(content).decode("ascii"),
                "encoding": "base64",
            })
            self.uploaded += 1
        except RuntimeError as e:
            # GitHub 对已存在对象可能返回 409/422；用 cat-file 无法判断远端，故按已有处理
            if "409" in str(e) or "422" in str(e):
                self.reused += 1
            else:
                raise
        return sha

    def build_tree(self, local_tree: str, parent_tree: str | None) -> str:
        """
        变造树：逐项比较 local_tree 与 parent_tree，
        - 子树：仅当两边 SHA 不同才递归
        - blob：仅当 SHA 不同才上传
        返回远端新建树（或可直接复用的 SHA）。
        """
        local_entries = ls_tree_raw(self.repo, local_tree)
        parent_entries: dict[str, tuple[str, str, str]] = {}
        if parent_tree:
            try:
                parent_entries = self.remote_tree_entries(parent_tree)
            except RuntimeError:
                parent_entries = {}

        # 父树条目里 type 可能是 tree；用它来判断对应位置在远端是否也是目录
        def parent_is_dir(entry) -> bool:
            if entry is None:
                return False
            mode, _sha, typ = entry
            return typ == "tree" or is_dir(mode)

        new_entries = []
        for name, (mode, sha) in sorted(local_entries.items()):
            p = parent_entries.get(name)
            if is_dir(mode):
                if p and p[1] == sha and parent_is_dir(p):
                    new_sha = sha
                else:
                    new_sha = self.build_tree(sha, p[1] if parent_is_dir(p) else None)
            else:
                if p and p[1] == sha and not parent_is_dir(p):
                    new_sha = sha
                    self.reused += 1
                else:
                    new_sha = self.ensure_blob(sha)
            new_entries.append({
                "path": name,
                "mode": mode,
                "type": "tree" if is_dir(mode) else "blob",
                "sha": new_sha,
            })

        if not new_entries:
            # 空树需显式构造（GitHub 要求至少一个 entry 或省略 base_tree）
            created = self.api.post("git/trees", {"tree": []})
            return created["sha"]

        created = self.api.post("git/trees", {"tree": new_entries})
        return created["sha"]

    def run(self, branch: str, api_base: str | None = None) -> None:
        """
        推送本地提交到远端分支。

        api_base=None（默认）：以远端 head SHA 为基准列举提交。
        api_base=<本地 ref>（如 origin/main）：**当远端 head 对象不在本地时**使用。
          触发场景：先前经 API 推送时 GitHub 重建了提交对象，本地又没能 fetch 回来，
          于是 `rev-list <远端SHA>..HEAD` 会报 Invalid revision range。
          此时以本地记录的 origin/<branch> 为基准即可——它的树与远端 head 相同，
          且是 HEAD 的祖先，服务端仍会判定为快进。
        """
        if api_base:
            base_sha = git_text(self.repo, "rev-parse", api_base).strip()
            base_tree = git_text(self.repo, "rev-parse", f"{base_sha}^{{tree}}").strip()
            print(f"基准（本地 ref {api_base}）= {base_sha[:12]}")
        else:
            base_sha = None
            base_tree = None

        # 取远端 head（用于判断快进与打印；api_base 模式下容忍取不到）
        remote_head_sha = None
        remote_head_tree = None
        try:
            remote_head = json.loads(subprocess.run(
                [GH, "api", f"repos/{self.api.repo}/git/ref/heads/{branch}"],
                stdout=subprocess.PIPE, encoding="utf-8", errors="replace",
            ).stdout)
            remote_head_sha = remote_head["object"]["sha"]
            remote_head_tree = json.loads(subprocess.run(
                [GH, "api", f"repos/{self.api.repo}/git/commits/{remote_head_sha}"],
                stdout=subprocess.PIPE, encoding="utf-8", errors="replace",
            ).stdout)["tree"]["sha"]
        except Exception as e:
            if not api_base:
                raise RuntimeError(f"取远端 head 失败且未指定 --api-base：{e}")

        if base_sha is None:
            base_sha, base_tree = remote_head_sha, remote_head_tree

        commits = git_text(self.repo, "rev-list", "--reverse", f"{base_sha}..HEAD").split()
        if not commits:
            print(f"基准 {base_sha[:12]} 已包含本地全部提交，无需推送。")
            return

        print(f"远端 {branch} 当前 = {(remote_head_sha or '(未知/未取到)')[:12]}")
        print(f"待推送提交 {len(commits)} 个")

        # 若远端 head 与基准树不同，说明两者内容有差异，需要提示（仍按快进尝试）
        if remote_head_tree and base_tree and remote_head_tree != base_tree:
            print("⚠️ 远端 head 树与本地基准树不一致——本机可能缺少远端对象，")
            print("   若推送被拒（非快进），请先 fetch 对齐后再试。")

        parent_sha = base_sha
        parent_tree = base_tree

        for c in commits:
            local_tree = git_text(self.repo, "rev-parse", f"{c}^{{tree}}").strip()
            message = git_text(self.repo, "log", "-1", "--pretty=%B", c)
            author_name = git_text(self.repo, "log", "-1", "--pretty=%an", c).strip()
            author_email = git_text(self.repo, "log", "-1", "--pretty=%ae", c).strip()
            author_date = git_text(self.repo, "log", "-1", "--pretty=%aI", c).strip()

            print(f"\n→ {c[:12]}  {message.splitlines()[0][:60]}")
            new_tree = self.build_tree(local_tree, parent_tree)
            print(f"   tree {new_tree[:12]}  (已上传 blob {self.uploaded}, 复用 {self.reused})")

            commit = self.api.post("git/commits", {
                "message": message,
                "tree": new_tree,
                "parents": [parent_sha],
                "author": {"name": author_name, "email": author_email, "date": author_date},
                "committer": {"name": author_name, "email": author_email, "date": author_date},
            })
            new_sha = commit["sha"]
            print(f"   commit {new_sha[:12]}")
            parent_sha, parent_tree = new_sha, new_tree

        # 更新分支引用（快进）
        self.api.post(f"git/refs/heads/{branch}", {"sha": parent_sha, "force": False})
        print(f"\n✅ 已推送 {branch} → {parent_sha}")
        print(f"   统计：上传 blob {self.uploaded} 个，复用 {self.reused} 个")


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="通过 GitHub Git Data API 推送本地提交")
    ap.add_argument("repo", help="本地仓库路径")
    ap.add_argument("repo_full", help="owner/name")
    ap.add_argument("branch", nargs="?", default="main")
    ap.add_argument("--api-base", default=None,
                    help="以本地 ref 为提交范围基准（如 origin/main）。"
                         "用于远端 head 对象不在本地、导致 Invalid revision range 的场景。")
    a = ap.parse_args(argv)

    if not os.path.exists(GH):
        print(f"找不到 gh CLI: {GH}")
        return 2
    Pusher(a.repo, a.repo_full).run(a.branch, api_base=a.api_base)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
