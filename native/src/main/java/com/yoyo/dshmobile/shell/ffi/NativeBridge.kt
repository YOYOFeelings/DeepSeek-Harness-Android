package com.yoyo.dshmobile.shell.ffi

/**
 * 原生（C++）桥：模块化中"原生层"的入口。JNI 实现见 cpp/native-lib.cpp。
 */
object NativeBridge {

  init {
    System.loadLibrary("nativedata")
  }

  /** 调用原生 C++ 返回一条字符串。 */
  external fun stringFromJNI(): String
}