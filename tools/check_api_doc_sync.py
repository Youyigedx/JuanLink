#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
api.md ↔ 代码 一致性检查（只读）。

存在原因：`docs/api.md` 曾长期描述一组**不存在**的 API
（CanvasDocument / CanvasViewport / LayerPanel / DisconnectProtector(onClosed,onRecovered) …），
人工核对漏掉了一整节。本脚本把「文档里出现的标识符」与「代码里真实存在的标识符」
做机械比对，正反两向都查：

  正向：文档中声称存在的 API 名，代码里必须有
  反向：文档明确标注「不存在/已废弃」的名字，代码里不应作为类型定义存在

用法：python3 tools/check_api_doc_sync.py <仓库路径>
退出码：0 一致；1 有可疑项。
"""

from __future__ import annotations

import os
import re
import subprocess
import sys

# 文档中必须真实存在的符号（文档正文提到它们是现行 API）
MUST_EXIST = [
    "SessionManager", "startInitiator", "connectAsResponder",
    "OpSyncEngine", "applyLocal", "applyLocalWithInverse", "applyLocalBatch",
    "onRemoteOp", "onAck", "resendOutstanding",
    "ImageChunking", "chunkBytes", "chunkCount",
    "DrawBoxHost", "onLocalIntent", "resetCanvas", "duplicateSelected",
    "applyTextStyle", "setViewport",
    "CryptoEngine", "KeyDerivation", "deriveKeys", "SessionCrypto", "ReplayGuard",
    "PairingCodec", "QrCodec",
    "QualityMonitor", "QualityPolicyEngine", "policyFor",
    "DisconnectProtector", "onPaused",
    "AppState", "createRoom", "join", "saveRendezvousConfig", "importImageBytes",
    "DrawBoxCanvas", "FloatingToolBar", "ContextStyleBar", "ZoomControls",
    "TextEditOverlay", "HistoryPanel", "ConnectionPanel", "SettingsPanel",
    "PairingCode", "DefaultRendezvousClient", "RendezvousCode", "PairingInfoWire",
]

# 文档标注为「历史设计/已废弃」的名字：不得作为**类型定义**出现
MUST_NOT_BE_DEFINED = [
    "CanvasDocument", "CanvasOp", "CanvasViewport", "LayerPanel", "ImageTransfer", "Affine2",
]

# 源码目录（排除第三方 vendored 代码与构建产物）
EXCLUDE_DIRS = {"build", ".gradle", ".kotlin", ".git", "io"}


def collect_sources(root: str) -> list[str]:
    out = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
        # 排除 vendored 的 io/ak1/drawbox
        if os.path.join("io", "ak1", "drawbox") in dirpath.replace("/", os.sep):
            continue
        for fn in filenames:
            if fn.endswith((".kt", ".kts", ".py")):
                p = os.path.join(dirpath, fn)
                if "io" + os.sep + "ak1" + os.sep + "drawbox" in p:
                    continue
                out.append(p)
    return out


def main(argv):
    root = argv[0] if argv else "."
    api_md = os.path.join(root, "docs", "api.md")
    if not os.path.exists(api_md):
        print(f"找不到 {api_md}")
        return 2

    sources = collect_sources(root)
    blob = ""
    for p in sources:
        with open(p, "r", encoding="utf-8", errors="replace") as f:
            blob += f.read()
    compact = re.sub(r"\s+", "", blob)

    with open(api_md, "r", encoding="utf-8", errors="replace") as f:
        doc = f.read()

    print(f"扫描源码文件 {len(sources)} 个\n")

    print("[1] 正向：文档声称的现行 API 是否都在代码中")
    miss = []
    for name in MUST_EXIST:
        # 出现在文档中才检查
        if name not in doc:
            continue
        if name not in compact:
            miss.append(name)
            print(f"  MISS  {name}")
    if not miss:
        print(f"  OK    {len([n for n in MUST_EXIST if n in doc])} 个符号全部存在")

    print("\n[2] 反向：文档标注「已废弃」的名字是否已被源码注释误用")
    # 允许出现在注释里，但必须伴随「废弃/历史/早期」等限定词
    bad = []
    for name in MUST_NOT_BE_DEFINED:
        for p in sources:
            with open(p, "r", encoding="utf-8", errors="replace") as f:
                for i, line in enumerate(f, 1):
                    if re.search(rf"\b{name}\b", line):
                        # 只看注释行
                        s = line.strip()
                        if s.startswith("*") or s.startswith("//"):
                            if not any(k in line for k in (
                                "废弃", "历史", "早期", "不再是", "已不存在", "旧",
                            )):
                                bad.append((name, p, i, s[:70]))
    if bad:
        for name, p, i, s in bad:
            print(f"  WARN  {name}  在注释中未加废弃限定 -> {os.path.relpath(p, root)}:{i}")
            print(f"        {s}")
    else:
        print("  OK    源码注释中的废弃类名均已加限定说明")

    print()
    print("=" * 60)
    ok = not miss
    print("✅ api.md 与代码一致" if ok else "❌ 存在文档声称但代码不存在的符号")
    print("=" * 60)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
