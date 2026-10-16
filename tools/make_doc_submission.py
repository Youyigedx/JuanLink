#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成软著登记用的「文档」交存打印版（软件说明书）。

依据（中国版权保护中心官方口径，见 docs/软著申请可行性审计.md 第七节）：
  - 文档提交：前 30 页 + 后 30 页，各连续 30 页；不足 60 页则全部提交
  - **文档每页 ≥ 30 行**（注意：源程序是 ≥50 行，两者不同）
  - 页眉标注「软件名称 + 版本号」，且**必须与申请表一致**
  - 页码：文档自目录起编号
  - PDF、纵向排版

与 `make_copyright_submission.py`（源程序）的区别
  - 每页 30 行（不是 50）
  - 按 **Markdown 语义**排版（标题、列表、表格、代码块），而不是逐行原样输出
    源码
  - 带**目录**（官方要求文档自目录起编号）
  - 会做 Markdown 表格的等宽对齐，保证打印后可读

内容组织：把分散的 md 拼成一份完整《软件说明书》，依次为
  封面 → 目录 → 一、软件概述(README) → 二、使用说明(user-guide)
  → 三、设计说明(protocol/data-structures/api) → 附录(构建环境、压测方案)

用法：
  python3 tools/make_doc_submission.py <仓库路径> [--name "Juan LinK"] [--version V1.0]
                                       [--out <输出目录>] [--lines-per-page 30]
"""

from __future__ import annotations

import argparse
import html
import os
import re
import sys

# 依赖同目录的源程序生成器，复用「分页 / 选取前后 30 页 / HTML 骨架」逻辑
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_copyright_submission import chunk_pages, select_first_last  # noqa: E402

# 组装顺序： (标题, 相对路径) —— 路径不存在则跳过并在报告中提示
SECTIONS = [
    ("一、软件概述", "README.md"),
    ("二、使用说明", "docs/user-guide.md"),
    ("三、设计说明", "docs/protocol.md"),
    ("三、设计说明（续）数据结构", "docs/data-structures.md"),
    ("三、设计说明（续）接口设计", "docs/api.md"),
    ("附录 A：构建环境与已知问题", "docs/构建环境与已知问题.md"),
    ("附录 B：长稳压测方案", "docs/stress-test.md"),
]

PAGE_WIDTH = 100          # 表格/分隔线的最大宽度（字符）


# ---------------------------------------------------------------- Markdown 渲染

def _disp_width(s: str) -> int:
    """按终端显示宽度估算：CJK 字符算 2，其余算 1。"""
    w = 0
    for ch in s:
        w += 2 if ('\u1100' <= ch <= '\u115f' or '\u2e80' <= ch <= '\ua4cf'
                   or '\uac00' <= ch <= '\ud7a3' or '\uf900' <= ch <= '\ufaff'
                   or '\ufe30' <= ch <= '\ufe6f' or '\uff00' <= ch <= '\uff60') else 1
    return w


def _pad(s: str, width: int) -> str:
    """按显示宽度右侧补空格。"""
    return s + " " * max(0, width - _disp_width(s))


def _wrap_cjk(text: str, width: int) -> list[str]:
    """按显示宽度折行（中文按 2 宽计算），保留原有行结构。"""
    out: list[str] = []
    for para in text.split("\n"):
        if _disp_width(para) <= width:
            out.append(para)
            continue
        cur, curw = "", 0
        for ch in para:
            cw = 2 if _disp_width(ch) == 2 else 1
            if curw + cw > width:
                out.append(cur)
                cur, curw = "", 0
            cur += ch
            curw += cw
        if cur:
            out.append(cur)
    return out


def _strip_inline(s: str) -> str:
    """去掉行内 Markdown 标记，保留纯文本（打印后仍可读）。"""
    s = re.sub(r"`([^`]*)`", r"\1", s)          # 行内代码
    s = re.sub(r"\*\*([^*]+)\*\*", r"\1", s)    # 粗体
    s = re.sub(r"(?<!\*)\*([^*]+)\*(?!\*)", r"\1", s)  # 斜体
    s = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", s)     # 链接 -> 文字
    return s


# 打印时无意义的行：HTML 片段、徽章、纯图片引用
_SKIP_LINE = re.compile(
    r"^\s*(</?div|<img|<br\s*/?>|!\[)"          # HTML 容器与图片
    r"|^\s*!\[[^\]]*\]\([^)]*\)\s*$"            # 纯图片行
)


def _is_noise(line: str) -> bool:
    """判断该行是否属于打印时应丢弃的噪声（HTML 标签、徽章等）。"""
    s = line.strip()
    if not s:
        return False
    if _SKIP_LINE.match(s):
        return True
    # 徽章行（README 里是一行一个 ![...](...)）
    if re.match(r"^!\[[^\]]*\]\(.*\)\s*$", s):
        return True
    return False


def _is_table_sep(line: str) -> bool:
    return bool(re.match(r"^\s*\|?[\s:|-]+\|[\s:|-]*$", line)) and "-" in line


def render_markdown(md: str, title_for_h1: str | None = None) -> list[tuple[str, str]]:
    """
    把 Markdown 渲染成 (kind, text) 行序列。
    kind ∈ {"file"(章节标题), "code"(普通正文行)}，复用源程序生成器的行类型，
    以保证 HTML 渲染一致。
    """
    rows: list[tuple[str, str]] = []
    lines = md.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    i, in_code = 0, False

    def emit(text: str):
        rows.append(("code", text))

    while i < len(lines):
        line = lines[i]

        # ---- 代码块：原样保留（等宽字体，缩进即结构）
        if line.strip().startswith("```"):
            in_code = not in_code
            if in_code:
                emit("  " + "-" * (PAGE_WIDTH - 2))
            else:
                emit("  " + "-" * (PAGE_WIDTH - 2))
            i += 1
            continue
        if in_code:
            emit("  " + line.rstrip())
            i += 1
            continue

        stripped = line.strip()

        # ---- 丢弃打印噪声（HTML 容器、徽章、纯图片行）
        if not in_code and _is_noise(line):
            i += 1
            continue

        # ---- 标题
        m = re.match(r"^(#{1,6})\s+(.*)$", stripped)
        if m:
            level, text = len(m.group(1)), _strip_inline(m.group(2).strip())
            if level in (1, 2):
                char = "=" if level == 1 else "-"
                emit("")
                emit(text)
                emit(char * min(PAGE_WIDTH, max(8, _disp_width(text) + 8)))
            else:
                # 三级及以下用缩进 + 前缀表示层级
                emit("")
                emit("  " * (level - 3) + "【" + text + "】")
            i += 1
            continue

        # ---- 水平线
        if re.match(r"^\s*([-*_])\s*(\1\s*){2,}$", line):
            emit("-" * PAGE_WIDTH)
            i += 1
            continue

        # ---- 表格：按列对齐成等宽文本
        if line.lstrip().startswith("|") and i + 1 < len(lines) and _is_table_sep(lines[i + 1]):
            block = []
            while i < len(lines) and lines[i].lstrip().startswith("|"):
                if not _is_table_sep(lines[i]):
                    cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                    block.append([_strip_inline(c) for c in cells])
                i += 1
            if block:
                ncol = max(len(r) for r in block)
                block = [r + [""] * (ncol - len(r)) for r in block]
                widths = [0] * ncol
                avail = PAGE_WIDTH - 3 * (ncol - 1) - 2
                for r in block:
                    for c in range(ncol):
                        widths[c] = max(widths[c], min(_disp_width(r[c]), max(6, avail // ncol)))
                # 超宽单元格折行输出
                for ri, r in enumerate(block):
                    cells = [_wrap_cjk(r[c], widths[c]) for c in range(ncol)]
                    height = max(len(x) for x in cells)
                    for ln in range(height):
                        parts = []
                        for c in range(ncol):
                            seg = cells[c][ln] if ln < len(cells[c]) else ""
                            parts.append(_pad(seg, widths[c]))
                        emit("  " + " | ".join(parts).rstrip())
                    if ri == 0:
                        emit("  " + "-+-".join("-" * widths[c] for c in range(ncol)))
            continue

        # ---- 列表
        m = re.match(r"^(\s*)([-*+]|\d+\.)\s+(.*)$", line)
        if m:
            indent, marker, text = m.group(1), m.group(2), _strip_inline(m.group(3))
            bullet = ("•" if marker in "-*+" else marker)
            prefix = "  " + indent + bullet + " "
            wrapped = _wrap_cjk(text, max(20, PAGE_WIDTH - _disp_width(prefix)))
            for k, seg in enumerate(wrapped):
                emit(prefix + seg if k == 0 else " " * _disp_width(prefix) + seg)
            i += 1
            continue

        # ---- 引用
        if stripped.startswith(">"):
            text = _strip_inline(stripped.lstrip(">").strip())
            for seg in _wrap_cjk(text, PAGE_WIDTH - 4):
                emit("  | " + seg)
            i += 1
            continue

        # ---- 空行 / 段落
        if not stripped:
            emit("")
            i += 1
            continue
        for seg in _wrap_cjk(_strip_inline(stripped), PAGE_WIDTH):
            emit(seg)
        i += 1

    return rows


# ---------------------------------------------------------------- 组装

def _paginate(rows, lines_per_page, pagebreak="pagebreak"):
    """
    按显式分页符切页，返回页列表（每页 ≤ lines_per_page 行）。

    ⚠️ 不能简单地把全部行按固定行数切开：那样目录条目、章节标题会被分页切断，
    页号也对不上。这里 (kind == pagebreak) 表示「在此另起一页」。
    """
    pages, cur = [], []
    for kind, text in rows:
        if kind == pagebreak:
            if cur:
                pages.append(cur)
                cur = []
            continue
        if len(cur) >= lines_per_page:
            pages.append(cur)
            cur = []
        cur.append((kind, text))
    if cur:
        pages.append(cur)
    return pages


def _pad_pages(pages, lines_per_page):
    """
    把每页内容补足到 lines_per_page 行（末页同样补足）。

    为什么必须补：官方明确「文档每页 ≥30 行」。若不补，封面、目录、各章末页
    只有十几行，构成材料瑕疵。这里用空行补齐——空行仍是行，且打印后视觉自然
    （页面留白），不会像重复填充文字那样显得像凑数。

    同理，源程序生成器每页恰好 50 行，本身不会出现短页。
    """
    out = []
    for pg in pages:
        pad = lines_per_page - len(pg)
        out.append(pg + [("code", "")] * max(0, pad))
    return out


def build_document(root: str, name: str, version: str, lines_per_page: int):
    """
    拼装说明书，返回 (rows, total_lines, total_pages, used, missing)。

    两遍法：
      第 1 遍：渲染封面 + 各章节，算出每章的真实起始页号
      第 2 遍：用真实页号生成目录，再与封面、章节拼成最终行序列
    这样目录页码才不会错（此前用线性估算，页码全部偏小）。
    """
    used, missing = [], []
    rendered: list[tuple[str, list[tuple[str, str]]]] = []
    for title, rel in SECTIONS:
        full = os.path.join(root, rel)
        if not os.path.exists(full):
            missing.append(rel)
            continue
        with open(full, "r", encoding="utf-8", errors="replace") as f:
            md = f.read()
        rendered.append((title, render_markdown(md)))
        used.append(rel)

    # ---- 封面（独占一页）
    cover: list[tuple[str, str]] = [
        ("file", name),
        ("code", ""),
        ("code", "软件说明书（用户手册 + 设计说明）"),
        ("code", ""),
        ("code", f"软件名称：{name}"),
        ("code", f"版本号：{version}"),
        ("code", "文档类型：用户手册 / 设计说明（合并提交）"),
        ("code", ""),
        ("code", "本文档为计算机软件著作权登记申请材料之「文档」部分。"),
        ("code", "页眉所示的软件名称与版本号与申请表一致。"),
        ("pagebreak", ""),
    ]

    # ---- 第 1 遍：封面 + 目录占位 + 章节，求出各章起始页
    # 目录行数 = 标题 1 + 分隔 1 + 说明 2 + 条目 N + 空行 1
    toc_head = [("file", "目录"), ("code", "=" * 8), ("code", ""),
                ("code", "  说明：页码为全文页码（含封面与目录）。"), ("code", "")]
    toc_len = len(toc_head) + len(rendered) + 1        # +1 = 目录后空行

    def assemble(toc_body: list[tuple[str, str]]):
        r = list(cover) + toc_head + toc_body + [("pagebreak", "")]
        for title, body in rendered:
            r.append(("file", title))
            r.append(("code", "=" * min(PAGE_WIDTH, max(8, _disp_width(title) + 8))))
            r.extend(body)
            r.append(("pagebreak", ""))                 # 每章另起一页
        return r

    def page_of_line(rows_now, target_index):
        """第 1 遍：找到 target_index 行所在的页号（1 基）。"""
        pages = _paginate(rows_now, lines_per_page)
        acc = 0
        for pno, pg in enumerate(pages, start=1):
            if acc + len(pg) > target_index:
                return pno
            acc += len(pg)
        return len(pages)

    # 迭代收敛：目录条目文本长度不影响行数（每条固定 1 行），所以一遍即可，
    # 但仍迭代 2 次以消除「目录占位长度」与被替换内容的差异
    mapping = [1] * len(rendered)
    for _ in range(3):
        probe_body = []
        for (title, _b), page in zip(rendered, mapping):
            probe_body.append(("code", f"  {title} " + "." * max(2, 60 - _disp_width(title)) + f" {page}"))
        probe = assemble(probe_body)
        # 定位各章标题行的绝对下标
        idx, cur = [], len(cover) + toc_len
        for title, body in rendered:
            idx.append(cur)                             # 指向 ("file", title)
            cur += 2 + len(body) + 1                    # title + 分隔 + body + pagebreak
        new_mapping = [page_of_line(probe, i) for i in idx]
        if new_mapping == mapping:
            break
        mapping = new_mapping

    # ---- 第 2 遍：用真实页号生成目录
    toc_body = []
    for (title, _b), page in zip(rendered, mapping):
        toc_body.append(("code", f"  {title} " + "." * max(2, 60 - _disp_width(title)) + f" {page}"))
    rows = assemble(toc_body)

    # 补足每页到 lines_per_page 行（官方要求文档每页 ≥30 行）
    pages = _pad_pages(_paginate(rows, lines_per_page), lines_per_page)
    total_lines = sum(len(p) for p in pages)
    return rows, total_lines, len(pages), used, missing, pages


def render_html(pages, selected, header, lines_per_page) -> str:
    body = []
    for i in selected:
        page_no = i + 1
        body.append('<div class="page">')
        body.append(
            f'<div class="hdr"><span class="hname">{html.escape(header)}</span>'
            f'<span class="hpage">{page_no}</span></div>'
        )
        body.append('<pre class="doc">')
        for kind, text in pages[i]:
            body.append(f'<span class="sec">{html.escape(text)}</span>' if kind == "file"
                        else html.escape(text))
        body.append("</pre></div>")
    return f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>软件说明书（交存文档） — {html.escape(header)}</title>
<style>
  @page {{ size: A4 portrait; margin: 12mm 14mm; }}
  html, body {{ margin: 0; padding: 0; background: #f5f5f5; }}
  .note {{ max-width: 190mm; margin: 12px auto; padding: 10px 14px; background: #fffbe6;
           border: 1px solid #e0c97f; font: 13px/1.6 system-ui, "Microsoft YaHei", sans-serif; color: #5a4a00; }}
  .page {{ width: 210mm; min-height: 297mm; box-sizing: border-box; padding: 12mm 14mm;
           background: #fff; margin: 0 auto 10px; page-break-after: always; }}
  .page:last-child {{ page-break-after: auto; }}
  .hdr {{ display: flex; justify-content: space-between; align-items: baseline;
          border-bottom: 1px solid #999; padding-bottom: 2mm; margin-bottom: 3mm;
          font: 11px/1.4 system-ui, "Microsoft YaHei", sans-serif; color: #333; }}
  .hpage {{ font-weight: 700; }}
  pre.doc {{ margin: 0; font: 10px/1.5 "Microsoft YaHei", "SimSun", monospace;
             white-space: pre; color: #111; }}
  .sec {{ display: block; font-weight: 700; color: #0a3d62; }}
  @media print {{
    html, body {{ background: #fff; }}
    .note {{ display: none; }}
    .page {{ margin: 0; width: auto; min-height: 0; padding: 0; page-break-after: always; }}
    .page:last-child {{ page-break-after: auto; }}
  }}
</style></head>
<body>
<div class="note">
  <b>打印说明</b>：本文件为软著登记「文档」交存打印版。请在浏览器中
  <b>Ctrl+P → 目标改为「另存为 PDF」→ 纵向 → 页边距默认</b>，即得 A4 纵向 PDF。<br>
  官方要求：文档前 30 页 + 后 30 页（不足 60 页则全部提交）、<b>每页 ≥30 行</b>、
  页眉为「软件名称 + 版本号」且须与申请表一致。本提示条不会打印。
</div>
{chr(10).join(body)}
</body></html>
"""


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--name", default="Juan LinK")
    ap.add_argument("--version", default="V1.0")
    ap.add_argument("--out", default=None)
    ap.add_argument("--lines-per-page", type=int, default=30)
    a = ap.parse_args(argv)

    root = os.path.abspath(a.root)
    out_dir = a.out or os.path.join(root, "out")
    os.makedirs(out_dir, exist_ok=True)

    header = f"{a.name} {a.version}"
    rows, total_lines, total_pages, used, missing, pages = build_document(
        root, a.name, a.version, a.lines_per_page)
    selected, rule_note = select_first_last(pages)

    html_path = os.path.join(out_dir, "交存文档-软件说明书.html")
    with open(html_path, "w", encoding="utf-8") as f:
        f.write(render_html(pages, selected, header, a.lines_per_page))

    txt_path = os.path.join(out_dir, "交存文档-软件说明书.txt")
    with open(txt_path, "w", encoding="utf-8") as f:
        for i in selected:
            f.write(f"{header}\t\t\t\t\t\t\t\t\t{i + 1}\n")
            for _k, text in pages[i]:
                f.write(text + "\n")

    print(f"页眉           : {header}")
    print(f"每页行数       : {a.lines_per_page}（官方要求文档 >=30）")
    print(f"总行数         : {total_lines:,}")
    print(f"总页数         : {total_pages}")
    print(f"提交规则       : {rule_note}")
    print(f"本次提交页数   : {len(selected)}")
    print(f"纳入素材       : {len(used)} 份")
    for u in used:
        print(f"    - {u}")
    if missing:
        print(f"缺失素材（已跳过）: {missing}")
    print()
    for p in (html_path, txt_path):
        print(f"  已生成  {p}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
