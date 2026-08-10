# 婵娟 JUAN Link — 通信协议文档

> 版本：1.0 · 状态：随开发演进（阶段 2 起，阶段 8 定稿）
> 实现位于 `core/src/commonMain/kotlin/com/juanlink/core/protocol/`、`transport/`、`session/`、`crypto/`。

## 1. 设计原则

- **纯 P2P**：无任何云服务器/中转/数据服务器。局域网 TCP 直连；公网 UDP 打洞（阶段 7 落地）。
- **端到端加密**：X25519 密钥交换 + AES-256-GCM 数据加密 + 包级 HMAC + 防重放。
- **带外信令**：二维码/配对码只承担「交换连接参数 + 公钥」，不做数据中继。
- **操作级同步**：只传增量操作，绝不同步整画布。

## 2. 帧格式

```
物理帧: [4B 大端长度][1B 类型][CBOR 负载]
长度 = 1 + CBOR 负载字节数（不含 4B 长度头）
```

| 类型 | 值 | 消息 | 说明 |
|---|---|---|---|
| Hello | 0x01 | 握手请求 | 明文一次 |
| HandshakeResponse | 0x02 | 握手响应 | 明文一次，含双向认证 MAC |
| EncryptedFrame | 0x10 | 加密数据帧 | 全部业务数据 |
| Ping | 0x21 | 往返延迟 | |
| Pong | 0x22 | 往返响应 | 回显 Probe 时间戳 |
| Ack | 0x23 | 操作确认 | lastAppliedSeq |
| QualityProbe | 0x24 | 质量探测 | seq + sendTs + 载荷大小 |
| Heartbeat | 0x25 | 心跳 | |
| SyncRequest | 0x26 | 增量补同步请求 | sinceSeq |
| Resync | 0x27 | 增量补同步数据 | 序列化 OpEnvelope 列表 |
| ResyncDone | 0x28 | 补同步结束 | |
| Bye | 0x3F | 主动断开 | |

控制帧（握手/Ping/Probe/Ack 等）明文传输；**业务数据一律走 EncryptedFrame**。

## 3. 握手流程（ECDH 双向认证）

```
A（二维码持有方）                    B（扫码方）
  │ 生成 X25519 密钥对，公钥进二维码   │
  │←── 二维码（sessionId, pkA, nonce, cands, ts, exp）──│
  │                                   │ 生成密钥对，连接 A
  │←────── Hello(sessionId, pkB) ────│
  │ S = ECDH(skA, pkB)               │
  │ keys = HKDF(S, sessionId‖nonce)  │
  │ 校验 hello.sessionId == 本端      │
  │──── HandshakeResponse(pkA, MAC) ─→│
  │                                   │ S' = ECDH(skB, pkA)
  │                                   │ keys' = HKDF(S', ...)
  │                                   │ 常量时间校验 MAC → 双向认证
  │══════ 加密通道建立 ══════│
```

- MAC = `HMAC-SHA256(macKey, pkB ‖ pkA ‖ sessionId ‖ nonce)`
- 密钥一次性：二维码过期即弃；会话结束即销毁。
- 防中间人：非对称侧由「公钥进二维码（带外可信）」保证；MAC 侧由 nonce 防重放。

## 4. 加密载荷（EncryptedFrame）

```
EncryptedFrame {
  seq:      Long       # 单调递增
  iv:       12B        # ivBase ⊕ seq（前 8 字节异或）
  ciphertext: var      # AES-256-GCM(encKey, iv, AAD=sessionId‖seq, plaintext)，含 16B tag
  mac:      32B        # HMAC-SHA256(macKey, seq‖iv‖ciphertext)
}
```

解密三重防线：**ReplayGuard 滑动窗口**（拒绝已见/过旧 seq）→ **MAC 校验**（常量时间）→ **GCM 认证解密**。任一失败即丢弃。

## 5. 操作同步

- **OpEnvelope**（CBOR，置于 EncryptedFrame 明文内）：
  `opId(lamport, peerId), peerId, opType, payload(CBOR of CanvasOp), seq, timestamp`
- **排序**：Lamport 全序（`lamport` 升序，`peerId` 决胜）+ 每端出站 `seq` 连续前沿缓冲。
- **Ack**：接收端按连续 seq 回 Ack(lastAppliedSeq)；发送端滑窗清除已确认，超时重发。
- **补同步**：重连后 `SyncRequest(sinceSeq)` → 对端从 applyLog 回放增量 → `Resync` + `ResyncDone`。
- **图片分块**：`ImageAdd`(元数据) → `ImageChunk`(16KB) → `ImageReady`(SHA-256 校验)；缺块经 `onImageResendNeeded` 请求重传。

## 6. 断线保护状态机

```
Connected --(断开)--> Reconnecting --(<3s 重连)--> Connected（无感恢复，重放未 ack）
                            |--(3s~5s)--> Paused（暂停同步，本地可画）
                            |--(>30s)--> Closed（自动关闭协作，保留 applyLog）
```

## 7. 质量探测

- 周期 1s 发 `QualityProbe(seq, sendTs, 32B)`；对端回 `Pong(seq, sendTs)`。
- 滑动窗口（近 20 样本）计算：延迟（RTT 中位数）、抖动（标准差）、丢包率。
- 分级：Excellent(<80ms,<1%) / Normal(80-200ms,1-5%) / Unstable(>200ms,>5%)。
- Unstable → 禁止实时协作，提示「当前双方网络环境无法保证实时交流稳定性，请改善网络环境后重新连接。」
- 全程无服务器；质量差不换路，只降级或中止。

## 8. 安全边界

- 二维码只含公钥，绝不含私钥；泄露公钥仅能发起临时会话。
- 密钥仅存内存，不落盘；会话隔离（每会话独立 sessionId + 派生密钥）。
- 本协议为**局域网 + 可达公网端点**设计；对称 NAT 后公网直连不可达（物理限制），UI 引导同网段连接。

## 9. 版本演进

- `PROTOCOL_VERSION = 1`；`magic = "juanlink"`。
- OpType 使用稳定编码（见 data-structures.md §2.2），向后兼容依赖此值不变。
