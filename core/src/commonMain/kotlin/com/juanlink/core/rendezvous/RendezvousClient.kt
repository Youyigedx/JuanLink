package com.juanlink.core.rendezvous

/**
 * 会合客户端（expect）。
 *
 * 平台实现：
 * - JVM / 桌面：`java.net.http.HttpClient`（JDK 11+，零第三方依赖）
 * - Android：API 33+ 同样内置 `java.net.http.HttpClient`
 *
 * 唯一要求：**必须在后台线程调用**（本接口是阻塞式）。
 * 调用方在 `AppState` 中统一经 `Dispatchers.IO` 派发，避免冻结 UI。
 */
expect object DefaultRendezvousClient : RendezvousClient