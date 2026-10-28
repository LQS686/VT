package com.hzy.libp7zip;

/**
 * JNI bridge to libp7zip.so (com.hzy:libp7zip, Apache-2.0).
 * Native symbols: Java_com_hzy_libp7zip_P7ZipApi_executeCommand / get7zVersionInfo.
 */
public final class P7ZipApi {

    static {
        System.loadLibrary("p7zip");
    }

    private P7ZipApi() {
    }

    public static native int executeCommand(String cmd);

    public static native String get7zVersionInfo();
}
