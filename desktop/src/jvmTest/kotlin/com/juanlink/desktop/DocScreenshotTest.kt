package com.juanlink.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.juanlink.composeui.AppState
import com.juanlink.composeui.platform.RendezvousConfigIo
import com.juanlink.composeui.platform.SnapshotIo
import com.juanlink.composeui.platform.TurnConfigIo
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.TextAlignment
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 软著「文档」配图生成器：把**真实 UI** 离屏渲染成 PNG。
 *
 * 为什么不用屏幕截图 / captureToImage
 * - 屏幕截图依赖窗口位置、前台状态、屏幕是否锁定 → 结果不确定；
 * - Compose Desktop **没有** `captureToImage`（那是 Android 的 API）。
 *
 * 方案：`androidx.compose.ui.ImageComposeScene`（Compose Desktop 的离屏渲染入口，
 * 公开 API）把真实 Composable 渲染成 Skia 位图并编码 PNG。
 * 渲染的是**同一个 `AppRoot` 与 `AppState`**，因此配图与软件实际界面一致。
 * 画布内容通过 `Intent.AddElement` 注入真实元素，而不是贴一张假图。
 *
 * 运行：
 *   ./gradlew :desktop:jvmTest --tests '*DocScreenshotTest*'
 * 输出：
 *   <仓库根>/assets/screenshots/ 下的各个 PNG
 */
class DocScreenshotTest {

    /** 已配置 TURN 的假持久化：避免首启弹出「请先配置 TURN」引导，遮挡主界面。 */
    private class FixedTurnIo : TurnConfigIo {
        override fun load(): String =
            """[{"host":"turn.example.com","port":3478,"username":"<用户名>","password":"<密码>"}]"""
        override fun save(text: String) {}
    }

    private class NoopSnapshotIo : SnapshotIo {
        override fun load(): String? = null
        override fun save(text: String) {}
    }

    private class NoopRendezvousIo : RendezvousConfigIo {
        override fun load(): String? = null
        override fun save(text: String) {}
    }

    private fun newApp(): AppState = AppState(
        snapshotIo = NoopSnapshotIo(),
        turnConfigIo = FixedTurnIo(),
        rendezvousConfigIo = NoopRendezvousIo(),
    )

    private fun repoRoot(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            if (d != null && File(d, "settings.gradle.kts").exists()) return d
            d = d?.parentFile
        }
        return File(System.getProperty("user.dir")).absoluteFile
    }

    private fun outDir(): File = File(repoRoot(), "assets/screenshots").apply { mkdirs() }

    // ---------------------------------------------------------------- 画布内容

    /** 用真实元素类型构造一条笔迹（正弦曲线，模拟手绘）。 */
    private fun strokePath(
        id: String,
        startX: Float, startY: Float, length: Float, amplitude: Float,
        color: Color, width: Float, z: Int,
    ): Element.Path {
        val n = 48
        val samples = (0..n).map { i ->
            val t = i.toFloat() / n
            Element.PathSample(
                position = Offset(startX + length * t, startY + amplitude * sin(t * 4f * Math.PI).toFloat()),
                width = width,
            )
        }
        return Element.Path(
            id = id, samples = samples, strokeColor = color,
            strokeWidth = width, alpha = 1f, zIndex = z,
        )
    }

    private fun shape(
        id: String, type: ShapeType,
        x1: Float, y1: Float, x2: Float, y2: Float,
        color: Color, z: Int,
    ): Element.Shape = Element.Shape(
        id = id,
        shapeType = type,
        // Shape 用点列表示：起止两点即可（直线/箭头/矩形/圆/三角均如此）
        points = listOf(Offset(x1, y1), Offset(x2, y2)),
        strokeColor = color,
        strokeWidth = 3f,
        zIndex = z,
    )

    /**
     * 给画布注入演示内容：笔迹 + 形状 + 文字，用**真实的 Element 类型**
     * 与 `Intent.AddElement`，与用户实际绘制走同一条路径。
     */
    private fun seedCanvas(app: AppState) {
        val ink = Color(0xFF1A1A1A)
        val zhuSha = Color(0xFFC0392B)
        val zhuQing = Color(0xFF2F6F5E)
        val tuoHuang = Color(0xFFB8860B)

        app.host.onLocalIntent(Intent.SetBgColor(Color(0xFFFAF7F0)))

        app.host.onLocalIntent(Intent.AddElement(
            strokePath("demo-1", 120f, 260f, 420f, 46f, zhuQing, 4f, 1)))
        app.host.onLocalIntent(Intent.AddElement(
            strokePath("demo-2", 160f, 430f, 360f, 30f, zhuSha, 3f, 2)))
        app.host.onLocalIntent(Intent.AddElement(
            shape("demo-r", ShapeType.RECTANGLE, 640f, 210f, 860f, 330f, ink, 3)))
        app.host.onLocalIntent(Intent.AddElement(
            shape("demo-c", ShapeType.CIRCLE, 900f, 200f, 1040f, 340f, tuoHuang, 3)))
        app.host.onLocalIntent(Intent.AddElement(
            shape("demo-t", ShapeType.TRIANGLE, 1080f, 200f, 1240f, 340f, zhuQing, 3)))
        app.host.onLocalIntent(Intent.AddElement(
            shape("demo-l", ShapeType.LINE, 640f, 400f, 1240f, 400f, ink, 2)))
        app.host.onLocalIntent(Intent.AddElement(
            shape("demo-a", ShapeType.ARROW, 640f, 460f, 940f, 460f, zhuSha, 3)))
        app.host.onLocalIntent(Intent.AddElement(
            Element.Text(
                id = "demo-txt",
                text = "千里共婵娟（演示笔迹）",
                fontSize = 26f,
                color = ink,
                alignment = TextAlignment.LEFT,
                topLeft = Offset(650f, 510f),
                wrapWidth = 600f,
                measuredHeight = 36f,
                zIndex = 8,
            )))
    }

    /** 离屏渲染一帧并存为 PNG。 */
    private fun renderPng(name: String, width: Int, height: Int, app: AppState): File {
        val file = File(outDir(), name)
        // 注意：ImageComposeScene 未实现 AutoCloseable，不能用 `use {}`，用 try/finally 收尾
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f))
        try {
            scene.setContent {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    AppRoot(appState = app)
                }
            }
            // 渲染两帧：第一帧应用生效中的状态变更并触发布局，第二帧取稳定结果。
            // 只渲染一帧可能在元素刚注入、重组尚未完成时截到空画布。
            scene.render()
            val image = scene.render()
            val data = image.encodeToData(EncodedImageFormat.PNG)
                ?: error("PNG 编码失败：$name")
            file.writeBytes(data.bytes)
        } finally {
            scene.close()
        }
        return file
    }

    /** 产出过小基本是纯色块 —— 用于捕捉「渲染成功但内容为空」的假成功。 */
    private fun looksBlank(f: File): Boolean = !f.exists() || f.length() < 3000

    @Test
    fun generateManualScreenshots() {
        val produced = mutableListOf<File>()
        val failures = mutableListOf<String>()

        fun attempt(name: String, app: AppState) {
            runCatching { renderPng(name, 1440, 900, app) }
                .onSuccess { f ->
                    if (looksBlank(f)) failures += "$name（疑似空白，${f.length()} B）" else produced += f
                }
                .onFailure { failures += "$name（渲染异常：${it.message}）" }
        }

        // 1) 主界面：工具栏 + 真实笔迹与形状（走 Intent.AddElement，与用户绘制同路径）
        attempt("01-主界面-画布与工具栏.png", newApp().apply { seedCanvas(this) })

        // 2) 连接面板（未配置会合服务器时的「加入」区）
        attempt("02-连接面板.png", newApp().apply { showConnection = true })

        // 3) 设置面板：TURN + 会合服务（短配对码）
        attempt("03-设置面板.png", newApp().apply { showSettings = true })

        // 4) 历史快照侧栏
        attempt("04-历史快照面板.png", newApp().apply { showHistory = true })

        println("[SHOT] 产出目录: ${outDir()}")
        println("[SHOT] 成功 ${produced.size} 张：")
        produced.forEach { println("   ${it.name}  ${it.length()} B") }
        if (failures.isNotEmpty()) {
            println("[SHOT] 失败 ${failures.size} 项：")
            failures.forEach { println("   $it") }
        }
        assertTrue(produced.isNotEmpty(), "至少应产出一张有效截图；失败项：$failures")
    }
}
