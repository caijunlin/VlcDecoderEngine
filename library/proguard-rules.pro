# 强制保留参数名（防止 event, width, height 被混淆成 a, b, c）
-keepparameternames

# 保留 Kotlin 的元数据，确保高阶函数类型在混淆后不出错
-keep class kotlin.Metadata { *; }

# X5StreamKit的包名和类名以及函数名不混淆
-keep class com.caijunlin.vlcdecoder.StreamKit { *; }

# JavaScriptBridge的类名和里面的函数名不混淆 (供 JS 调用的桥接类必须防混淆)
-keepclassmembers class com.caijunlin.vlcdecoder.debug.JavaScriptBridge {
    # 保留该类下的所有公开方法
    public <methods>;
}

# callback包下面的所有类和函数名不混淆
-keep class com.caijunlin.vlcdecoder.callback.** { *; }

# StreamWebView类名不混淆 (仅保留类名，不要求保留内部函数/变量名，所以用 keepnames)
-keep class com.caijunlin.vlcdecoder.core.StreamWebView {
    public <init>(...);
}

-dontwarn java.lang.invoke.StringConcatFactory