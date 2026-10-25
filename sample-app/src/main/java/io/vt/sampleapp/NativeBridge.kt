package io.vt.sampleapp

object NativeBridge {
    @JvmStatic
    fun greeting(): String {
        System.loadLibrary("sample")
        return nativeGreeting()
    }

    private external fun nativeGreeting(): String
}
