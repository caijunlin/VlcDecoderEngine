# 保持包名
-keeppackagenames com.caijunlin.**
-keeppackagenames org.videolan.**

# 保护 VlcBridge 的 JNI 逻辑，防止主工程 R8 误杀 native 方法
-keepclasseswithmembernames class com.caijunlin.vlcdecoder.**.VlcBridge {
    native <methods>;
}

# 保护对外暴露的 API，确保主工程能正常调用
-keep public class com.caijunlin.vlcdecoder.**.VlcStreamManager {
    public <methods>;
}

# 保护 VLC 官方内部反射逻辑
-keep class org.videolan.** { *; }

# 保护对外的配置枚举
-keep public enum com.caijunlin.vlcdecoder.core.RenderApi {
    **[] $VALUES;
    public *;
}