# 允许更激进的优化
-optimizationpasses 5
-allowaccessmodification
-dontusemixedcaseclassnames
-dontwarn **
-verbose

# 同样需要引入 Keep 规则，防止自己把自己混淆坏了
-keeppackagenames com.caijunlin.**
-keeppackagenames org.videolan.**

-keepclasseswithmembernames class com.caijunlin.vlcdecoder.**.VlcBridge {
    native <methods>;
}

-keep public class com.caijunlin.vlcdecoder.**.VlcStreamManager {
    public <methods>;
}

-keep class org.videolan.** { *; }

# 保护对外的配置枚举
-keep public enum com.caijunlin.vlcdecoder.core.RenderApi {
    **[] $VALUES;
    public *;
}