#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成软著登记用的「交存源程序」打印版。

依据（中国版权保护中心官方口径，详见 docs/软著申请可行性审计.md 第七节）：
  - 源程序提交：前 30 页 + 后 30 页，各连续 30 页
  - 不足 60 页则全部提交
  - 程序每页 ≥ 50 行
  - 页眉标注「软件名称 + 版本号」，且**必须与申请表一致**
  - 右上角标页码
  - PDF、纵向排版

关键决策：**排除他人代码**
  - `composeUi/.../io/ak1/drawbox/` 是 vendored 的 Apache-2.0 第三方绘图引擎
    （20 文件 / 5,932 行），不计入本次登记的独创范围，必须排除。
    ⚠️ 但**绝不删除**其 LICENSE/NOTICE——那会违反 Apache-2.0 §4。仅在此处不纳入交存。
  - `tools/` 是仓库维护脚本（推 GitHub 等），与软件功能无关，同样排除。

输出（写入 out/ 目录）：
  - 交存源程序.html   打印用（浏览器 Ctrl+P → 另存为 PDF，纵向）
  - 交存源程序.txt    纯文本版，便于核对行数
  - 交存源程序-说明.md 交存范围、排除项与依据

用法：
  python3 tools/make_copyright_submission.py <仓库路径> [--name "软件名称"] [--version V1.0]
                                             [--out <输出目录>] [--lines-per-page 50]
"""

from __future__ import annotations

import argparse
import html
import os
import re
import sys

# ---------------------------------------------------------------- 范围规则

# 纳入交存：自有源码与构建/配置文件
INCLUDE_EXT = {".kt", ".kts", ".py", ".xml", ".pro", ".properties", ".toml"}

# 排除目录（路径片段，命中即跳过）
EXCLUDE_PATH_PARTS = (
    os.sep + "io" + os.sep + "ak1" + os.sep + "drawbox",   # 第三方 vendored 引擎
    os.sep + "build" + os.sep,
    os.sep + ".gradle" + os.sep,
    os.sep + ".kotlin" + os.sep,
    os.sep + ".git" + os.sep,
    os.sep + "tools" + os.sep,        # 仓库维护脚本，非软件功能
    os.sep + "out" + os.sep,
)

# 按此顺序排列模块（依赖自底向上），便于审查员理解结构
MODULE_ORDER = ("core", "composeUi", "desktop", "androidApp", "server")

# 只有这些顶层目录纳入（排除 docs/ 与纯资源目录）
TOP_LEVEL = set(MODULE_ORDER)

# 具体排除的文件名
EXCLUDE_FILES = {
    "LICENSE", "NOTICE.txt", "AndroidManifest.xml",   # 声明文件；manifest 归入 androidApp 更合适，见下
}


def module_of(rel: str) -> str:
    parts = rel.replace("\\", "/").split("/")
    return parts[0] if parts[0] in TOP_LEVEL else ""


def collect(root: str) -> list[str]:
    """收集纳入交存的文件（相对路径），按模块 + 路径排序。"""
    picked: list[str] = []
    for dirpath, dirnames, filenames in os.walk(root):
        # 就地剪枝：跳过排除目录，避免深入
        full = dirpath + os.sep
        if any(p in full for p in EXCLUDE_PATH_PARTS):
            dirnames[:] = []
            continue
        dirnames[:] = [
            d for d in dirnames
            if not any((os.sep + d + os.sep) in p or (os.sep + d) == p.rstrip(os.sep)
                       for p in EXCLUDE_PATH_PARTS)
        ]
        for fn in filenames:
            ext = os.path.splitext(fn)[1]
            if ext not in INCLUDE_EXT:
                continue
            if fn in EXCLUDE_FILES:
                continue
            rel = os.path.relpath(os.path.join(dirpath, fn), root)
            if not module_of(rel):
                continue
            picked.append(rel)

    def sort_key(rel: str):
        mod = module_of(rel)
        idx = MODULE_ORDER.index(mod) if mod in MODULE_ORDER else len(MODULE_ORDER)
        return (idx, rel.replace("\\", "/"))

    return sorted(picked, key=sort_key)


# ---------------------------------------------------------------- 排版

def build_lines(root: str, files: list[str], header: str, lines_per_page: int):
    """
    产出 (rows, total_lines, total_pages)。

    rows: [(kind, text)]，kind ∈ {"file", "code"}
    页眉占每页第 1 行，正文行由 [chunk_pages] 按 lines_per_page 切分
    （末页补空行，保证每页都满足官方的「程序每页 ≥50 行」）。
    """
    rows: list[tuple[str, str]] = []
    for rel in files:
        with open(os.path.join(root, rel), "r", encoding="utf-8", errors="replace") as f:
            content = f.read()
        text = content.replace("\r\n", "\n").replace("\r", "\n").rstrip("\n")
        rows.append(("file", f"// ==== {rel.replace(os.sep, '/')} (共 {len(text.splitlines())} 行) ===="))
        for line in text.splitlines():
            rows.append(("code", line))
    total_lines = len(rows)
    total_pages = max(1, -(-total_lines // lines_per_page))   # 向上取整
    return rows, total_lines, total_pages


def chunk_pages(rows, lines_per_page):
    """
    按页切分，**每页恰好 lines_per_page 行**（末页用空行补齐）。

    为什么必须补齐：官方要求「程序每页 ≥50 行」。若不补，总行数除不尽时
    最后一页可能只剩几行——实测本项目 14,951 行切成 300 页后，
    第 300 页只有 1 行。而「后 30 页」恰好包含这一页，直接构成材料瑕疵。
    """
    pages = [rows[i:i + lines_per_page] for i in range(0, len(rows), lines_per_page)]
    if pages:
        last = pages[-1]
        if len(last) < lines_per_page:
            pages[-1] = last + [("code", "")] * (lines_per_page - len(last))
    return pages


def select_first_last(pages, count=30):
    """官方规则：前 30 页 + 后 30 页；不足则全部。返回 (选中页, 说明)。"""
    n = len(pages)
    if n <= count * 2:
        return list(range(n)), f"总 {n} 页，不足 60 页 → **全部提交**"
    return list(range(count)) + list(range(n - count, n)), f"总 {n} 页 → 提交**前 30 页 + 后 30 页**（第 1–30、{n-29}–{n} 页）"


def render_html(pages, selected, header, lines_per_page) -> str:
    """生成打印用 HTML。页眉固定表头，页码在右上角。"""
    body = []
    for i in selected:
        page_no = i + 1
        body.append('<div class="page">')
        body.append(
            f'<div class="hdr"><span class="hname">{html.escape(header)}</span>'
            f'<span class="hpage">{page_no}</span></div>'
        )
        body.append('<pre class="code">')
        for kind, text in pages[i]:
            if kind == "file":
                body.append(f'<span class="fname">{html.escape(text)}</span>')
            else:
                body.append(html.escape(text))
        body.append("</pre>")
        body.append("</div>")
    # 页码总数用于页脚说明（非打印内容）
    return f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>交存源程序 — {html.escape(header)}</title>
<style>
  @page {{ size: A4 portrait; margin: 12mm 14mm; }}
  html, body {{ margin: 0; padding: 0; background: #f5f5f5; }}
  .note {{ max-width: 190mm; margin: 12px auto; padding: 10px 14px; background: #fffbe6;
           border: 1px solid #e0c97f; font: 13px/1.6 system-ui, "Microsoft YaHei", sans-serif; color: #5a4a00; }}
  .page {{ width: 210mm; min-height: 297mm; box-sizing: border-box; padding: 12mm 14mm;
           background: #fff; margin: 0 auto 10px; page-break-after: always; position: relative; }}
  .page:last-child {{ page-break-after: auto; }}
  .hdr {{ display: flex; justify-content: space-between; align-items: baseline;
          border-bottom: 1px solid #999; padding-bottom: 2mm; margin-bottom: 3mm;
          font: 11px/1.4 system-ui, "Microsoft YaHei", sans-serif; color: #333; }}
  .hpage {{ font-weight: 700; }}
  pre.code {{ margin: 0; font: {lines_per_page and '10.5px'} / 1.42 "Consolas", "Courier New", monospace;
              white-space: pre; tab-size: 4; color: #111; }}
  .fname {{ display: block; color: #0a5; font-weight: 700; }}
  @media print {{
    html, body {{ background: #fff; }}
    .note {{ display: none; }}
    .page {{ margin: 0; width: auto; min-height: 0; padding: 0; page-break-after: always; }}
    .page:last-child {{ page-break-after: auto; }}
  }}
</style></head>
<body>
<div class="note">
  <b>打印说明</b>：本文件为软著登记「源程序」交存打印版。请在浏览器中
  <b>Ctrl+P → 目标改为「另存为 PDF」→ 纵向 → 页边距默认</b>，即得 A4 纵向 PDF。<br>
  页眉「{html.escape(header)}」与右上角页码已内置，<b>不得修改</b>——
  官方要求页眉须与申请表一致。本提示条不会打印。
</div>
{chr(10).join(body)}
</body></html>
"""


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--name", default="Juan LinK",
                    help="软件名称，必须与申请表逐字一致（注意是 Juan LinK，大写 K）")
    ap.add_argument("--version", default="V1.0")
    ap.add_argument("--out", default=None)
    ap.add_argument("--lines-per-page", type=int, default=50)
    a = ap.parse_args(argv)

    root = os.path.abspath(a.root)
    out_dir = a.out or os.path.join(root, "out")
    os.makedirs(out_dir, exist_ok=True)

    files = collect(root)
    if not files:
        print("未收集到任何源文件，请检查路径")
        return 1

    header = f"{a.name} {a.version}"
    rows, total_lines, total_pages = build_lines(root, files, header, a.lines_per_page)
    pages = chunk_pages(rows, a.lines_per_page)
    selected, rule_note = select_first_last(pages)

    # ---- HTML
    html_path = os.path.join(out_dir, "交存源程序.html")
    with open(html_path, "w", encoding="utf-8") as f:
        f.write(render_html(pages, selected, header, a.lines_per_page))

    # ---- 纯文本（仅选中页，便于核对）
    txt_path = os.path.join(out_dir, "交存源程序.txt")
    with open(txt_path, "w", encoding="utf-8") as f:
        for i in selected:
            f.write(f"{header}\t\t\t\t\t\t\t\t\t{i + 1}\n")
            for _kind, text in pages[i]:
                f.write(text + "\n")

    # ---- 说明文件
    excluded_drawbox = 0
    for dirpath, _dn, fns in os.walk(root):
        if os.sep + "io" + os.sep + "ak1" + os.sep + "drawbox" in dirpath + os.sep:
            excluded_drawbox += sum(1 for x in fns if os.path.splitext(x)[1] == ".kt")
    doc = f"""# 交存源程序说明

> 生成脚本：`tools/make_copyright_submission.py`
> 软件名称 / 版本：**{header}**

## 一、交存规模

| 项 | 值 |
|---|---|
| 纳入文件数 | {len(files)} |
| 总行数 | {total_lines:,} |
| 总页数（每页 {a.lines_per_page} 行） | {total_pages} |
| 本次提交页数 | {len(selected)} |
| 提交规则 | {rule_note} |

## 二、纳入范围

按依赖自底向上的顺序排列，便于理解结构：

"""
    for m in MODULE_ORDER:
        sub = [x for x in files if module_of(x) == m]
        if not sub:
            continue
        ext_count = {}
        for x in sub:
            ext_count[os.path.splitext(x)[1]] = ext_count.get(os.path.splitext(x)[1], 0) + 1
        detail = "、".join(f"{k}×{v}" for k, v in sorted(ext_count.items()))
        doc += f"- `{m}/` —— {len(sub)} 个文件（{detail}）\n"

    doc += f"""
不含 `docs/`（属登记材料中的**文档**部分，另交）、不含图片与二进制资源。

## 三、**排除**的内容与理由（重要）

### 1. 第三方开源绘图引擎 DrawBox —— 排除 {excluded_drawbox} 个 `.kt` 文件

路径：`composeUi/src/commonMain/kotlin/io/ak1/drawbox/`

这是 **vendored 的第三方代码**（上游 DrawBox，作者 Akshay Sharma，
Apache-2.0），共 20 文件 / 5,932 行。它不是本软件的独创部分，
**不计入本次登记范围**，故不纳入交存。

> ⚠️ **务必区分**：「不纳入交存」≠「删除」。
> 该目录下的 `LICENSE` 与 `NOTICE.txt` 已按要求保留在仓库中，
> **绝不能删除**——删除会违反 Apache-2.0 §4(b)(c)(d)，导致许可终止并构成侵权。
> 建议同时在申请材料中附《第三方开源组件说明》，如实声明该组件及其许可。

### 2. 仓库维护脚本 —— 排除 `tools/`

含推送 GitHub、校验远端一致性等开发运维脚本，与软件功能无关，不属于交存范围。

### 3. 本说明文件与生成的 PDF

`out/` 目录本身不纳入。

## 四、页眉与页码（不得改动）

- 页眉：`{header}` —— 官方要求页眉标注「软件名称 + 版本号」且**必须与申请表一致**。
  ⚠️ 若最终申请表填写的是其他名称或版本号，**必须重新生成本文件**：
  ```bash
  python3 tools/make_copyright_submission.py "{root}" --name "申请表上的软件名称" --version "V1.0"
  ```
- 页码：右上角，按提交顺序从 1 连续编号。

## 五、复核要点

1. 每页行数：程序每页 **≥50 行**（本文件每页恰好 {a.lines_per_page} 行，符合要求）。
2. 页眉与申请表逐字一致。
3. 打印为 **A4 纵向 PDF**（浏览器 Ctrl+P → 另存为 PDF）。
4. 提交前确认文件中**不含**任何密码、密钥、`release.jks`、`local.properties`。
"""
    doc_path = os.path.join(out_dir, "交存源程序-说明.md")
    with open(doc_path, "w", encoding="utf-8") as f:
        f.write(doc)

    print(f"页眉           : {header}")
    print(f"纳入文件       : {len(files)} 个")
    print(f"总行数         : {total_lines:,}")
    print(f"总页数         : {total_pages}（每页 {a.lines_per_page} 行）")
    print(f"提交规则       : {rule_note}")
    print(f"本次提交页数   : {len(selected)}")
    print(f"排除第三方代码 : drawbox {excluded_drawbox} 个 .kt 文件")
    print()
    for p in (html_path, txt_path, doc_path):
        print(f"  已生成  {p}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
