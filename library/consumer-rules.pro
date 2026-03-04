# 强制保留参数名（防止 event, width, height 被混淆成 a, b, c）
-keepparameternames

# 保留 Kotlin 的元数据，确保高阶函数类型在混淆后不出错
-keep class kotlin.Metadata { *; }

# 绝对保留 VlcStreamManager 的类名以及内部所有的公开/私有成员和方法
-keep class com.caijunlin.vlcdecoder.core.VlcStreamManager { *; }

# 保留 VideoGestureHelper 类名、构造函数以及指定的 onTouchEvent 方法
-keep class com.caijunlin.vlcdecoder.gesture.VideoGestureHelper {
    # 保留初始化函数（构造方法），匹配任意参数
    public <init>(...);

    # 保留 onTouchEvent 函数名、参数类型和返回值
    public boolean onTouchEvent(android.view.MotionEvent, int, int);
}
