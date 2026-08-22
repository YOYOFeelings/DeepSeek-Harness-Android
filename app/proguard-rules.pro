# 模块化重构骨架：当前不启用代码混淆，原生桥预留规则（启用 R8 时保留 JNI 符号）。
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.yoyo.dshmobile.shell.ffi.** { *; }