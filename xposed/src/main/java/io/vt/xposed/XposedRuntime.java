package io.vt.xposed;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.content.res.XResources;

import java.io.File;
import java.util.List;

/** Entry point used by the container to install LSPlant and load enabled modules. */
public final class XposedRuntime {
    private static volatile Context context;
    private static volatile File prefsMirrorDir;

    private XposedRuntime() {
    }

    public static synchronized boolean init(Context host) {
        if (context == null && host != null) {
            context = host.getApplicationContext();
            XposedLog.init(new File(context.getFilesDir(), "logs"));
            prefsMirrorDir = new File(context.getFilesDir(), "module_prefs");
            //noinspection ResultOfMethodCallIgnored
            prefsMirrorDir.mkdirs();
            XSharedPreferencesBridge.setMirror(prefsMirrorDir);
        }
        return VtNative.ensureLoaded();
    }

    public static boolean isReady() {
        return VtNative.isInitialized();
    }

    public static int getArtHookVersion() {
        return 93;
    }

    public static List<String> loadModules(List<ModuleSpec> modules, ClassLoader guestClassLoader,
                                           ApplicationInfo guestAppInfo, Resources guestResources,
                                           String processName) {
        if (!isReady()) {
            XposedLog.log("Xposed runtime is not initialized; modules are skipped");
            return java.util.Collections.singletonList("LSPlant 未初始化，模块已跳过");
        }
        return ModuleLoader.load(modules, guestClassLoader, guestAppInfo, guestResources, processName);
    }

    static File getPrefsMirrorDir() {
        return prefsMirrorDir;
    }

    /** Keeps the API-facing XSharedPreferences independent from this package. */
    private static final class XSharedPreferencesBridge {
        static void setMirror(File dir) {
            de.robv.android.xposed.XSharedPreferences.setMirrorBase(dir);
        }
    }
}
