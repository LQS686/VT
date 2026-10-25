/*
 * Legacy Xposed API runtime for VT. API shape follows the original XposedBridge;
 * implementation is backed by LSPlant through io.vt.xposed.Hooker.
 */
package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import io.vt.xposed.Hooker;
import io.vt.xposed.XposedLog;

import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * This class contains most of Xposed's central logic, such as initialization and callbacks used by
 * the native side. It also includes methods to add new hooks.
 */
public final class XposedBridge {
    public static final ClassLoader BOOTCLASSLOADER = XposedBridge.class.getClassLoader();
    public static final String TAG = "VT-Xposed";

    @Deprecated
    public static final int XPOSED_BRIDGE_VERSION = 93;

    private static final Object[] EMPTY_ARRAY = new Object[0];
    private static final ConcurrentHashMap<Member, Hooker> HOOKS = new ConcurrentHashMap<>();

    public static final CopyOnWriteArraySet<XC_LoadPackage> sLoadedPackageCallbacks =
            new CopyOnWriteArraySet<>();
    /*package*/ static final CopyOnWriteArraySet<XC_InitPackageResources> sInitPackageResourcesCallbacks =
            new CopyOnWriteArraySet<>();

    private XposedBridge() {
    }

    public static int getXposedVersion() {
        return XPOSED_BRIDGE_VERSION;
    }

    public static void log(String text) {
        XposedLog.log(text);
    }

    public static void log(Throwable t) {
        XposedLog.log(t);
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        if (!(hookMethod instanceof Executable)) {
            throw new IllegalArgumentException("Only methods and constructors can be hooked: " + hookMethod);
        }
        Executable executable = (Executable) hookMethod;
        if (Modifier.isAbstract(executable.getModifiers())) {
            throw new IllegalArgumentException("Cannot hook abstract methods: " + executable);
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }

        Hooker hooker = HOOKS.get(executable);
        if (hooker == null) {
            Hooker candidate = new Hooker(executable);
            if (!candidate.install()) {
                log("Failed to install native hook for " + executable);
            }
            Hooker previous = HOOKS.putIfAbsent(executable, candidate);
            hooker = previous == null ? candidate : previous;
        }
        hooker.addCallback(callback);
        return callback.new Unhook(executable);
    }

    public static void unhookMethod(Member hookMethod, XC_MethodHook callback) {
        if (!(hookMethod instanceof Executable)) {
            return;
        }
        Hooker hooker = HOOKS.get(hookMethod);
        if (hooker == null) {
            return;
        }
        hooker.removeCallback(callback);
        if (hooker.isEmpty()) {
            HOOKS.remove(hookMethod, hooker);
            try {
                io.vt.xposed.VtNative.unhook((Executable) hookMethod);
            } catch (Throwable t) {
                log(t);
            }
        }
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName,
                                                           XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new HashSet<>();
        for (Method method : hookClass.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                unhooks.add(hookMethod(method, callback));
            }
        }
        return unhooks;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass,
                                                                XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new HashSet<>();
        for (Constructor<?> constructor : hookClass.getDeclaredConstructors()) {
            unhooks.add(hookMethod(constructor, callback));
        }
        return unhooks;
    }

    public static void hookLoadPackage(XC_LoadPackage callback) {
        sLoadedPackageCallbacks.add(callback);
    }

    public static void hookInitPackageResources(XC_InitPackageResources callback) {
        sInitPackageResourcesCallbacks.add(callback);
    }

    public static boolean isMethodHooked(Member method) {
        if (!(method instanceof Executable)) {
            return false;
        }
        Hooker hooker = HOOKS.get(method);
        return hooker != null && hooker.isInstalled();
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws Throwable {
        if (!(method instanceof Executable)) {
            throw new IllegalArgumentException("method must be of type Method or Constructor");
        }
        if (args == null) {
            args = EMPTY_ARRAY;
        }
        Hooker hooker = HOOKS.get(method);
        if (hooker != null && hooker.isInstalled()) {
            return hooker.invokeBackup(thisObject, args);
        }
        if (method instanceof Method) {
            Method m = (Method) method;
            m.setAccessible(true);
            return m.invoke(Modifier.isStatic(m.getModifiers()) ? null : thisObject, args);
        }
        Constructor<?> c = (Constructor<?>) method;
        c.setAccessible(true);
        return c.newInstance(args);
    }

    public static void deoptimizeMethod(Member method) {
        if (method instanceof Executable) {
            try {
                io.vt.xposed.VtNative.deoptimize((Executable) method);
            } catch (Throwable t) {
                log(t);
            }
        }
    }

    /** Resource hooking is not implemented in the lightweight runtime yet. */
    public static void initXResources() {
        log("Resource API: initXResources is not supported by this build");
    }

    public static void hookResources() {
        log("Resource API: hookResources is not supported by this build");
    }

    public static final class CopyOnWriteSortedSet<E> {
        private transient volatile Object[] elements = EMPTY_ARRAY;

        public synchronized boolean add(E e) {
            int index = indexOf(e);
            if (index >= 0) {
                return false;
            }
            Object[] newElements = Arrays.copyOf(elements, elements.length + 1);
            newElements[elements.length] = e;
            Arrays.sort(newElements);
            elements = newElements;
            return true;
        }

        public synchronized boolean remove(E e) {
            int index = indexOf(e);
            if (index == -1) {
                return false;
            }
            Object[] newElements = new Object[elements.length - 1];
            System.arraycopy(elements, 0, newElements, 0, index);
            System.arraycopy(elements, index + 1, newElements, index, elements.length - index - 1);
            elements = newElements;
            return true;
        }

        public synchronized void clear() {
            elements = EMPTY_ARRAY;
        }

        private int indexOf(Object o) {
            for (int i = 0; i < elements.length; i++) {
                if (o.equals(elements[i])) {
                    return i;
                }
            }
            return -1;
        }

        public Object[] getSnapshot() {
            return elements;
        }

        @SuppressWarnings("unchecked")
        public <T> T[] getSnapshot(T[] array) {
            return (T[]) Arrays.copyOf(elements, elements.length, array.getClass());
        }
    }
}
