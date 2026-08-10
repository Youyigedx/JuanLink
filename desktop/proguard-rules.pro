# 婵娟 JUAN Link — 桌面 ProGuard 规则
# 业务内核依赖 kotlinx.serialization 反射与 @Serializable 类，需整体保留
-keep class com.juanlink.core.** { *; }
# ZXing 二维码解码
-keep class com.google.zxing.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, Annotation
