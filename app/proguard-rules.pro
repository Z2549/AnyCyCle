# Xposed 入口与反射调用类不能混淆
-keep class io.github.z2549.anycycle.xposed.** { *; }

# SignalRingView.applyConfig 由 MainHook 反射调用（已整体 keep，双保险）
-keepclassmembers class io.github.z2549.anycycle.xposed.SignalRingView {
    public void applyConfig(java.lang.String);
}

# 平台内置 org.json 在编译期可见，混淆器可能报缺引用
-dontwarn org.json.**

# libxposed 接口由框架运行时提供
-dontwarn io.github.libxposed.**

# Compose/AndroidX 常规配置
-dontwarn androidx.**
