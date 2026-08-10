package com.juanlink.desktop

import com.juanlink.composeui.AppState
import com.juanlink.core.transport.TurnServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验证「首次使用必须先配置 TURN 服务器」闸门语义（纯 AppState 层，不启真实网络）。
 *
 * 默认构造（InMemoryTurnConfigIo → load()=null）模拟首次启动：turnServers 为空 → 自动弹引导。
 */
class SetupGateTest {

    private fun validServer() = TurnServer("turn.example.com", 3478, "user", "pass")

    @Test
    fun freshAppShowsSetupByDefault() {
        val app = AppState()
        assertTrue(app.showSetup, "未配置 TURN → 首次启动应自动弹引导")
        assertFalse(app.setupLocked, "首次启动引导应可关闭")
    }

    @Test
    fun createRoomBlockedWhenUnconfigured() {
        val app = AppState()
        app.createRoom()
        assertTrue(app.showSetup, "未配置点创建协作 → 弹强制引导")
        assertTrue(app.setupLocked, "点协作触发 → 引导锁定不可关闭")
        assertTrue(app.statusText.contains("TURN"), "应提示先配置 TURN，实际: ${app.statusText}")
        assertNull(app.pairing, "被拦截时应不产出配对信息")
    }

    @Test
    fun joinBlockedBeforeParsingWhenUnconfigured() {
        val app = AppState()
        app.join("not-a-real-payload")
        // 闸门在解析连接串之前生效：提示配置而非"连接串无效"
        assertTrue(app.statusText.contains("TURN"), "应先提示配置 TURN，实际: ${app.statusText}")
        assertTrue(app.showSetup && app.setupLocked, "应弹强制引导")
    }

    @Test
    fun saveConfigReleasesGate() {
        val app = AppState()
        app.createRoom() // 触发锁定引导
        assertTrue(app.setupLocked)

        app.saveTurnConfig(listOf(validServer()))
        assertFalse(app.showSetup, "保存了服务器 → 关闭引导")
        assertFalse(app.setupLocked, "保存了服务器 → 解锁")
        assertEquals(1, app.turnServers.size)
    }

    @Test
    fun saveEmptyListKeepsGate() {
        val app = AppState()
        app.createRoom()
        app.saveTurnConfig(emptyList())
        assertTrue(app.showSetup, "保存空列表 = 仍未配置 → 引导保持")
        assertTrue(app.setupLocked, "保存空列表 → 仍锁定")
    }

    @Test
    fun dismissOnlyWorksWhenNotLocked() {
        val app = AppState()
        // 首次启动引导可关闭
        app.dismissSetup()
        assertFalse(app.showSetup, "非锁定引导可关闭")

        // 点协作触发锁定引导后，dismiss 无效
        app.createRoom()
        assertTrue(app.showSetup && app.setupLocked)
        app.dismissSetup()
        assertTrue(app.showSetup, "锁定引导 dismiss 应被忽略")
    }

    @Test
    fun skipSetupReleasesGate() {
        val app = AppState()
        app.createRoom()
        assertTrue(app.setupLocked)

        app.skipSetup()
        assertFalse(app.showSetup, "跳过 → 关闭引导")
        assertFalse(app.setupLocked, "跳过 → 解锁")
        assertTrue(app.setupSkipped, "跳过 → 本会话不再拦截")
    }
}
