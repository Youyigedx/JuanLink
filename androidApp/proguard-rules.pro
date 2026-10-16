# Juan LinK — Android R8 规则
# 业务内核依赖 kotlinx.serialization 反射与 @Serializable 类，需整体保留
-keep class com.juanlink.core.** { *; }
# ZXing 二维码解码
-keep class com.google.zxing.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, Annotation

# ---------------------------------------------------------------- java.net.http
# 会合客户端（DefaultRendezvousClient）用 JDK 内置的 java.net.http.HttpClient，
# 而 **Android 的 android.jar 里没有 java.net.http.***（该类自 Android 13 / API 33
# 起在设备运行时提供，但未纳入编译用 SDK）。
# 因此 R8 在全量压缩（release + minifyEnabled）时会报「Missing class」并失败：
#   ERROR: R8: Missing class java.net.http.HttpClient …
# 本应用 minSdk = 33，运行时必然存在，故仅需抑制编译期告警。
# 规则来自 AGP 自动生成的 androidApp/build/outputs/mapping/release/missing_rules.txt。
-dontwarn java.net.http.HttpClient
-dontwarn java.net.http.HttpClient$Builder
-dontwarn java.net.http.HttpClient$Redirect
-dontwarn java.net.http.HttpRequest
-dontwarn java.net.http.HttpRequest$BodyPublisher
-dontwarn java.net.http.HttpRequest$BodyPublishers
-dontwarn java.net.http.HttpRequest$Builder
-dontwarn java.net.http.HttpResponse
-dontwarn java.net.http.HttpResponse$BodyHandler
-dontwarn java.net.http.HttpResponse$BodyHandlers
