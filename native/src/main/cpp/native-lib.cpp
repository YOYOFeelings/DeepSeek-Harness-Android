#include <jni.h>
#include <string>

// 模块化标注的「原生 C++」层：暴露简单的 JNI 示例供 Java/Kotlin 交替调用。
// 后续可在此承载需高性能/系统底层的模块（加解密、压缩、内存管线等）。
extern "C" JNIEXPORT jstring JNICALL
Java_com_yoyo_dshmobile_shell_ffi_NativeBridge_stringFromJNI(
    JNIEnv *env,
    jobject /* this */) {
  return env->NewStringUTF("Hello from native C++ (yoyo)");
}