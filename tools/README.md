# tools/ — 仓库维护脚本

这些脚本用于在 **github.com 的 git 协议被网络阻断**时维护本仓库。
它们不属于产品功能，不影响软件运行，也不参与打包。

---

## 背景：为什么需要它们

本机（中国大陆网络环境）实测：

| 端点 | 结果 |
|---|---|
| `https://github.com`（git smart-HTTP） | **连接被重置**（`Recv failure: Connection was reset`） |
| `https://api.github.com` | **可达**（HTTP 200） |
| `ssh.github.com:443` | TCP 可达，但本机公钥未注册到 GitHub 账号 |

因此 `git push` 无法直接使用。而 `gh` CLI 的 token 具备 `repo` 权限，
所以改用 GitHub 的 **Git Data API**（blobs / trees / commits / refs）完成推送。

**这不是配置问题，是网络层限制**。若你所在网络能直连 github.com，
直接用 `git push` 即可，无需本目录任何脚本。

---

## 脚本

### `git_push_via_api.py` — 通过 API 推送提交

```bash
python3 tools/git_push_via_api.py "<仓库路径>" "<owner/repo>" [分支]
```

做三件事：

1. 取远端 head SHA，以它为基准算出待推送的提交（**不能用 `main..HEAD`**——
   本地 main 已指向新提交，该范围是空集）；
2. 逐层构造树：与父树对比，**只上传真正变化的对象**，未变子树直接复用远端 SHA；
3. 创建 commit 与 ref，完成快进推送。

**正确性保证**：每个 blob 上传前重新计算 `sha1("blob <len>\0" + content)`
并与本地对象哈希比对，不一致立即中止，绝不写入远端。

> ⚠️ 两个实现陷阱（都已踩过并修正，改代码时勿回退）：
> - 目录判定**不能**用 `mode.endswith("4")`——文件模式 `100644` 也以 4 结尾，
>   会把 blob 当树递归，报 `fatal: not a tree object`。必须用精确模式 `40000`。
> - 远端父树的条目**不能**用 `git ls-tree` 读——远端对象本地不存在。
>   必须走 API（`git/trees/{sha}`）。

### `verify_remote.py` — 校验远端内容与本地逐字节一致

```bash
python3 tools/verify_remote.py "<仓库路径>" "<owner/repo>"
```

把远端 main 的整棵树递归拉下来，与本地 HEAD 的树**逐文件比对 SHA 与 mode**，
报告「仅本地存在 / 仅远端存在 / 内容不一致」三类差异。

API 推送返回成功**不代表内容正确**，所以这一步是必需的。

### `verify_commit_chain.py` — 校验提交消息、作者与父链

```bash
python3 tools/verify_commit_chain.py "<仓库路径>" "<owner/repo>" [分支]
```

核对远端提交的 message / author 与本地一致，且父链连续。

> 注意：`/git/commits` 端点把 `message`、`author` 放在**顶层**，
> 不同于 `/repos/{o}/{r}/commits` 的 `commit.*` 嵌套结构。

---

## 一个必须知道的事实：提交 SHA 会变化

API 重建提交对象时，committer 字段的编码与本地 git 不同，
因此**远端提交 SHA ≠ 本地提交 SHA**（实测：本地 `dbdd2cc2` → 远端 `819fe70a`）。

**这是良性的**：

- 树对象完全相同（实测两边 tree 均为 `75674132…`，158 个文件，差异为 0）；
- 提交消息、作者、父链完全一致；
- 父链正确接在原有历史之上，属**快进**推送，无分叉。

但请知悉两个后果：

1. 下次 `git pull` 会因提交 SHA 不同而合并/重写本地这两个提交；
2. **开发完成日期等需要与提交哈希对应的材料，请以本地 `git log` 为准**
   （那才是你的真实开发记录），不要把远端 SHA 写进任何申报材料。

---

## 更省事的替代方案

把本机 SSH 公钥注册到 GitHub 账号后，走 SSH 通道即可正常推送，不必用 API：

```
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIE3OcQgnRDaRFwcTPfVxx6eeSKij7E+49pif+zh3bySh juanlink-desktop
```

GitHub → Settings → SSH and GPG keys → New SSH key，粘贴以上内容，
然后配置 `~/.ssh/config` 走 443 端口（22 端口常被封）：

```
Host github.com
  HostName ssh.github.com
  Port 443
  User git
```

之后把 remote 改为 `git@github.com:Youyigedx/JuanLink.git` 即可。
