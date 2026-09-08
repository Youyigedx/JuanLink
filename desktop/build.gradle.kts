// Juan LinK — :desktop Compose Desktop 壳（Windows/macOS）
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    sourceSets {
        jvmMain.dependencies {
            // Compose 组件版本由 compose 插件统一管理（material3 独立版本号可能与主版本不同）
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.foundation)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(project(":core"))
            // 共享 UI（画布/工具栏/面板/连接面板/AppState 全部来自 :composeUi）
            implementation(project(":composeUi"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(compose.desktop.currentOs)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(project(":core"))
            implementation(project(":composeUi"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.juanlink.desktop.MainKt"
        // SystemFont（宋体按名称加载）需要访问 sun.font 封闭包
        jvmArgs += listOf("--add-opens", "java.desktop/sun.font=ALL-UNNAMED")
        nativeDistributions {
            // 不显式声明安装包格式，避免触发 WiX 下载（GitHub 源慢）。
            // 用 createDistributable 生成免安装应用目录；需要安装包时再配置 targetFormats。
            packageName = "JuanLink"
            packageVersion = "1.0.0"
            description = "Juan LinK"
            // 启动器 exe 图标（Windows 需 .ico，运行时窗口图标在 Main.kt 用 painterResource 设置）
            windows {
                iconFile.set(file("src/jvmMain/resources/icon.ico"))
            }
        }
        // 桌面瘦身：release 构建启用 ProGuard（混淆 + 删无用代码），把依赖 jar 从 48M 压到十几 MB。
        // compose 插件自动附带 skia/skiko/coroutines/compose 的默认 keep 规则。
        buildTypes.release.proguard {
            isEnabled.set(true)
            configurationFiles.from(file("proguard-rules.pro"))
        }
    }
}
