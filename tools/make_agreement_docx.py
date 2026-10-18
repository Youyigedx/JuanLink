# -*- coding: utf-8 -*-
"""
从单一内容源生成《合作开发协议》的 .docx。

设计目的：**消除 docx 与 pdf 两份成品的内容漂移**。
两者此前各自维护文本——改条款时容易只改一边。现在统一以
`tools/make_agreement_pdf.py` 里的 `CONTENT` 为唯一内容源，
本脚本把它转换为 officecli 的 batch 指令来构建 docx。

用法：
  python tools/make_agreement_docx.py [输出路径]
"""

import json
import os
import subprocess
import sys
import tempfile

from make_agreement_pdf import CONTENT

# CONTENT 里的样式名 → docx 段落属性
DOCX_PROPS = {
    "title": {
        "size": "22pt", "bold": "true", "font.ea": "黑体",
        "font.latin": "Times New Roman", "align": "center", "spaceAfter": "20pt",
    },
    "h_party": {
        "size": "12pt", "bold": "true", "font.ea": "黑体",
        "spaceBefore": "12pt", "spaceAfter": "6pt", "lineSpacing": "1.5x",
    },
    "info": {"size": "12pt", "lineSpacing": "1.5x"},
    "h_article": {
        "size": "14pt", "bold": "true", "font.ea": "黑体",
        "spaceBefore": "14pt", "spaceAfter": "8pt",
        "keepNext": "true", "lineSpacing": "1.5x",
    },
    "body": {"size": "12pt", "firstLineIndent": "24pt", "lineSpacing": "1.5x"},
    "center": {
        "size": "12pt", "align": "center",
        "spaceBefore": "24pt", "spaceAfter": "24pt",
    },
    "sig": {"size": "12pt", "lineSpacing": "1.5x", "spaceBefore": "14pt"},
    "h_appendix": {
        "size": "14pt", "bold": "true", "font.ea": "黑体",
        "pageBreakBefore": "true", "spaceBefore": "0pt",
        "spaceAfter": "10pt", "lineSpacing": "1.5x",
    },
    "apx_sub": {
        "size": "12pt", "bold": "true", "font.ea": "黑体",
        "spaceBefore": "12pt", "spaceAfter": "4pt", "lineSpacing": "1.5x",
    },
    "apx_body": {"size": "12pt", "firstLineIndent": "24pt", "lineSpacing": "1.5x"},
}


def build_batch():
    """把 CONTENT 转成 officecli batch 指令列表。"""
    items = [
        {"command": "set", "path": "/", "props": {
            "docDefaults.font": "Times New Roman",
            "docDefaults.font.eastAsia": "宋体",
            "docDefaults.fontSize": "12pt",
            "marginTop": "2.54cm", "marginBottom": "2.54cm",
            "marginLeft": "3.17cm", "marginRight": "3.17cm",
        }},
    ]
    for key, text in CONTENT:
        if key in ("spacer", "pagebreak"):
            continue                      # 间距由 spaceBefore 表达；分页由 h_appendix 的 pageBreakBefore
        props = dict(DOCX_PROPS[key])
        props["text"] = text
        items.append({"command": "add", "parent": "/body", "type": "paragraph", "props": props})
    return items


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", shell=True, **kw)
    return r


def main(argv):
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = argv[0] if argv else os.path.join(root, "out", "软著", "合作开发协议.docx")
    os.makedirs(os.path.dirname(out), exist_ok=True)

    if os.path.exists(out):
        # 覆盖前先让 officecli 释放可能持有的句柄
        run(f'officecli close "{out}"')

    r = run(f'officecli create "{out}"')
    if r.returncode not in (0, 1) or not os.path.exists(out):
        print("create 失败:", r.stdout, r.stderr, file=sys.stderr)
        return 1

    batch = build_batch()
    fd, batch_path = tempfile.mkstemp(suffix=".json", text=True)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(batch, f, ensure_ascii=False, indent=1)

    r = run(f'officecli batch "{out}" --input "{batch_path}" --json')
    os.unlink(batch_path)
    try:
        summary = json.loads(r.stdout)["data"]["summary"]
        print(f"段落构建  total={summary['total']} ok={summary['succeeded']} "
              f"failed={summary['failed']}")
    except Exception:
        print("batch 输出解析失败:", r.stdout[:800], r.stderr[:800], file=sys.stderr)
        return 1

    # 页脚：第 X 页　共 Y 页（PAGE + NUMPAGES 字段）
    run(f'officecli add "{out}" / --type footer --prop type=default --prop size=9pt '
        f'--prop align=center --prop "text=第 " --prop field=page')
    run(f'officecli add "{out}" "/footer[1]/p[1]" --type run --prop "text= 页　共 " --prop size=9pt')
    run(f'officecli add "{out}" "/footer[1]/p[1]" --type field --prop fieldType=numpages --prop size=9pt')
    run(f'officecli add "{out}" "/footer[1]/p[1]" --type run --prop "text= 页" --prop size=9pt')

    print(f"已生成  {out}")

    v = run(f'officecli validate "{out}"')
    text = (v.stdout or "") + (v.stderr or "")
    print("校验    " + ("通过" if "no errors found" in text else text.strip()[:300]))

    run(f'officecli close "{out}"')
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
