package io.vt.xposed;

import java.io.File;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

/** JNI bridge to LSPlant. Loaded in guest processes only. */
public final class VtNative {
    private static volatile boolean loaded;
    private static volatile boolean initialized;

    private VtNative() {
    }

    public static synchronized boolean ensureLoaded() {
        if (!loaded) {
            try {
                System.loadLibrary("appcore");
                loaded = true;
                initialized = nativeIsInitialized();
            } catch (Throwable t) {
                XposedLog.log("Native library load failed: " + t);
                loaded = true;
                initialized = false;
            }
        }
        return initialized;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static native boolean nativeIsInitialized();

    public static native String nativeVersion();

    public static native Method hook(Executable target, Object hooker);

    public static native boolean unhook(Executable target);

    public static native boolean isHooked(Executable target);

    public static native boolean makeDexTrusted(File file);

    public static native boolean deoptimize(Executable method);

    public static native boolean installCrashHandler(String path);

    public static native boolean installNativeTrace(String path);

    public static native boolean installEnvShim(String hostPackage, String guestPackage, String realApk);

}
