package io.vt.xposed;

import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.content.res.XResources;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.PathClassLoader;

import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Loads legacy Xposed modules inside a guest process. */
final class ModuleLoader {
    private static final Set<String> LOADED_ZYGOTE = new HashSet<>();
    private static final Set<String> LOADED_PACKAGES = new HashSet<>();

    private ModuleLoader() {
    }

    static List<String> load(List<ModuleSpec> modules, ClassLoader guestClassLoader,
                             ApplicationInfo guestAppInfo, Resources guestResources,
                             String processName) {
        if (modules == null || modules.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> report = new ArrayList<>();
        String guestPackage = guestAppInfo == null ? "" : guestAppInfo.packageName;
        String loadKey = guestPackage + "@" + processName;
        synchronized (LOADED_PACKAGES) {
            if (!LOADED_PACKAGES.add(loadKey)) {
                return Collections.singletonList("该进程的模块已加载，跳过重复初始化");
            }
        }

        XC_LoadPackage.LoadPackageParam loadParam =
                new XC_LoadPackage.LoadPackageParam(new CopyOnWriteArraySet<>());
        loadParam.packageName = guestPackage;
        loadParam.processName = processName;
        loadParam.classLoader = guestClassLoader;
        loadParam.appInfo = guestAppInfo;
        loadParam.isFirstApplication = true;

        XResources xResources = null;
        if (guestResources != null) {
            try {
                xResources = new XResources(guestResources.getAssets(),
                        guestResources.getDisplayMetrics(), guestResources.getConfiguration());
            } catch (Throwable t) {
                XposedLog.log("Cannot create XResources: " + t);
            }
        }
        XC_InitPackageResources.InitPackageResourcesParam resParam =
                new XC_InitPackageResources.InitPackageResourcesParam(new CopyOnWriteArraySet<>());
        resParam.packageName = guestPackage;
        resParam.res = xResources;

        for (ModuleSpec module : modules) {
            String key = module.packageName + "@" + processName;
            try {
                List<String> entries = readEntries(module.apkPath);
                if (entries.isEmpty()) {
                    String message = "[" + module.label + "] 未找到 assets/xposed_init 或 META-INF/xposed/java_init.list";
                    XposedLog.log(message);
                    report.add(message);
                    continue;
                }
                ModuleClassLoader classLoader =
                        new ModuleClassLoader(module.apkPath, XposedRuntime.class.getClassLoader());
                XposedLog.log("加载模块 " + module.label + " (" + module.packageName + "), entry=" + entries);
                for (String entry : entries) {
                    loadEntry(module, entry, classLoader, loadParam, resParam, key);
                }
                report.add(module.label + " 已加载");
            } catch (Throwable t) {
                String message = "[" + module.label + "] 加载失败: " + t;
                XposedLog.log(message);
                XposedLog.log(t);
                report.add(message);
            }
        }
        return report;
    }

    private static void loadEntry(ModuleSpec module, String entry, ClassLoader classLoader,
                                  XC_LoadPackage.LoadPackageParam loadParam,
                                  XC_InitPackageResources.InitPackageResourcesParam resParam,
                                  String key) {
        try {
            Class<?> clazz = Class.forName(entry, true, classLoader);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            boolean handled = false;

            if (instance instanceof IXposedHookZygoteInit) {
                synchronized (LOADED_ZYGOTE) {
                    if (LOADED_ZYGOTE.add(module.packageName + "@" + ProcessHandle.currentProcess())) {
                        IXposedHookZygoteInit.StartupParam startup = newStartupParam();
                        startup.modulePath = module.apkPath;
                        startup.startsSystemServer = false;
                        ((IXposedHookZygoteInit) instance).initZygote(startup);
                        XposedLog.log("[" + module.label + "] initZygote 完成");
                    }
                }
                handled = true;
            }
            if (instance instanceof IXposedHookLoadPackage) {
                ((IXposedHookLoadPackage) instance).handleLoadPackage(loadParam);
                XposedLog.log("[" + module.label + "] handleLoadPackage 完成: "
                        + loadParam.packageName + "@" + loadParam.processName);
                handled = true;
            }
            if (instance instanceof IXposedHookInitPackageResources) {
                if (resParam.res == null) {
                    XposedLog.log("[" + module.label + "] 资源替换跳过：无 guest Resources");
                } else {
                    ((IXposedHookInitPackageResources) instance)
                            .handleInitPackageResources(resParam);
                    XposedLog.log("[" + module.label + "] 资源回调完成（字符串替换有限支持）");
                }
                handled = true;
            }
            if (!handled) {
                XposedLog.log("[" + module.label + "] 入口 " + entry + " 未实现任何 Xposed 接口");
            }
        } catch (Throwable t) {
            XposedLog.log("[" + module.label + "] 入口 " + entry + " 失败: " + t);
            XposedLog.log(t);
        }
    }

    private static IXposedHookZygoteInit.StartupParam newStartupParam() throws Exception {
        Constructor<IXposedHookZygoteInit.StartupParam> constructor =
                IXposedHookZygoteInit.StartupParam.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static List<String> readEntries(String apkPath) throws IOException {
        List<String> entries = new ArrayList<>();
        try (ZipFile zip = new ZipFile(new File(apkPath))) {
            readEntry(zip, "assets/xposed_init", entries);
            readEntry(zip, "META-INF/xposed/java_init.list", entries);
        }
        return entries;
    }

    private static void readEntry(ZipFile zip, String path, List<String> out) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            return;
        }
        try (InputStream input = zip.getInputStream(entry);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && !out.contains(line)) {
                    out.add(line);
                }
            }
        }
    }

    private static final class ProcessHandle {
        static String currentProcess() {
            try {
                return android.app.Application.getProcessName();
            } catch (Throwable ignored) {
                return "";
            }
        }
    }

    /** Child-first for module classes, parent-first for the Xposed API and platform classes. */
    static final class ModuleClassLoader extends PathClassLoader {
        ModuleClassLoader(String apkPath, ClassLoader parent) {
            super(apkPath, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("de.robv.android.xposed.")
                    || name.startsWith("android.")
                    || name.startsWith("androidx.annotation.")
                    || name.startsWith("java.")
                    || name.startsWith("javax.")
                    || name.startsWith("dalvik.")
                    || name.startsWith("sun.")
                    || name.startsWith("libcore.")
                    || name.startsWith("io.vt.xposed.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (this) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException ignored) {
                        loaded = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
