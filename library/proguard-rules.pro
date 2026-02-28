# 绝对保留 VlcStreamManager 的类名以及内部所有的公开/私有成员和方法
-keep class com.caijunlin.vlcdecoder.core.VlcStreamManager { *; }

# 保留 LibVLC 核心库下的所有类结构和 JNI 方法映射
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**

# 明确指示编译器不要对 Android 原生系统包及其子类进行混淆
-keep class android.** { *; }
-dontwarn android.**
-dontwarn java.lang.invoke.StringConcatFactory