package io.vt.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XC_MethodHook;

/**
 * One native ART hook per target method. LSPlant replaces the method with a generated
 * dispatcher whose callback receives [this, args...] and whose backup method executes
 * the original body on the original receiver.
 */
public final class Hooker {
    private final Executable target;
    private final boolean isStatic;
    private final boolean isConstructor;
    private final CopyOnWriteArrayList<XC_MethodHook> callbacks = new CopyOnWriteArrayList<>();
    private volatile Method backup;
    private final ThreadLocal<Boolean> inCallback = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    public static void beginBypass() {
        BYPASS.set(true);
    }

    public static void endBypass() {
        BYPASS.set(false);
    }

    public Hooker(Executable target) {
        this.target = target;
        this.isStatic = Modifier.isStatic(target.getModifiers());
        this.isConstructor = target instanceof Constructor;
    }

    public boolean install() {
        try {
            Method installed = VtNative.hook(target, this);
            if (installed == null) {
                return false;
            }
            Method resolved = findBackup(installed);
            resolved.setAccessible(true);
            backup = resolved;
            return true;
        } catch (Throwable t) {
            XposedLog.log("Hook installation failed for " + target + ": " + t);
            return false;
        }
    }

    private static Method findBackup(Method returned) {
        if ("backup".equals(returned.getName())) return returned;
        for (Method candidate : returned.getDeclaringClass().getDeclaredMethods()) {
            if ("backup".equals(candidate.getName())) return candidate;
        }
        // LSPlant can expose its cloned backup as a method carrying the target's metadata.
        return returned;
    }

    public void addCallback(XC_MethodHook callback) {
        callbacks.addIfAbsent(callback);
    }

    public void removeCallback(XC_MethodHook callback) {
        callbacks.remove(callback);
    }

    public boolean isEmpty() {
        return callbacks.isEmpty();
    }

    public boolean isInstalled() {
        return backup != null;
    }

    Executable getTarget() {
        return target;
    }

    /**
     * Called by the LSPlant generated dispatcher. Do not change this signature.
     */
    public Object callback(Object[] rawArgs) throws Throwable {
        if (Boolean.TRUE.equals(BYPASS.get())) {
            Object[] safeArgs = rawArgs == null ? new Object[0] : rawArgs;
            if (isStatic) {
                return invokeBackup(null, safeArgs);
            }
            Object receiver = safeArgs.length > 0 ? safeArgs[0] : null;
            return invokeBackup(receiver, Arrays.copyOfRange(safeArgs, 1, safeArgs.length));
        }
        if (Boolean.TRUE.equals(inCallback.get())) {
            throw new IllegalStateException("Recursive hook invocation: " + target);
        }
        inCallback.set(true);
        try {
        XC_MethodHook.MethodHookParam<Executable> param = new XC_MethodHook.MethodHookParam<>();
        param.method = target;
        if (isStatic) {
            param.thisObject = null;
            param.args = rawArgs == null ? new Object[0] : rawArgs.clone();
        } else {
            if (rawArgs == null || rawArgs.length == 0) {
                throw new IllegalStateException("Non-static hook callback without receiver: " + target);
            }
            param.thisObject = rawArgs[0];
            param.args = Arrays.copyOfRange(rawArgs, 1, rawArgs.length);
        }

        List<XC_MethodHook> snapshot = new ArrayList<>(callbacks);
        snapshot.sort(Comparator.comparingInt((XC_MethodHook cb) -> cb.priority).reversed());

        int beforeCount = 0;
        for (XC_MethodHook callback : snapshot) {
            beforeCount++;
            try {
                callback.callBeforeHookedMethod(param);
            } catch (Throwable t) {
                XposedLog.log("beforeHookedMethod failed for " + target + ": " + t);
                param.result = null;
                param.throwable = null;
                param.returnEarly = false;
                continue;
            }
            if (param.returnEarly) {
                break;
            }
        }

        if (!param.returnEarly) {
            try {
                param.result = invokeBackup(param.thisObject, param.args);
                param.throwable = null;
            } catch (InvocationTargetException e) {
                param.result = null;
                param.throwable = e.getCause() == null ? e : e.getCause();
            } catch (Throwable t) {
                param.result = null;
                param.throwable = t;
            }
        }

        for (int i = Math.min(beforeCount, snapshot.size()) - 1; i >= 0; i--) {
            XC_MethodHook callback = snapshot.get(i);
            Object lastResult = param.result;
            Throwable lastThrowable = param.throwable;
            try {
                callback.callAfterHookedMethod(param);
            } catch (Throwable t) {
                XposedLog.log("afterHookedMethod failed for " + target + ": " + t);
                param.result = lastResult;
                param.throwable = lastThrowable;
            }
        }

        if (isConstructor) {
            // Constructors are dispatched as void by ART; the initialized receiver is the result.
            if (param.throwable != null) {
                throw param.throwable;
            }
            return param.thisObject;
        }
        if (param.throwable != null) {
            throw param.throwable;
        }
        return param.result;
        } finally {
            inCallback.set(false);
        }
    }

    public Object invokeBackup(Object thisObject, Object[] args) throws Throwable {
        Method method = backup;
        if (method == null) {
            return invokeDirect(thisObject, args);
        }
        int expected = method.getParameterCount();
        Object[] full = args;
        if (!isStatic) {
            full = new Object[args.length + 1];
            full[0] = thisObject;
            System.arraycopy(args, 0, full, 1, args.length);
        }
        if (!isStatic) {
            // LSPlant's backup Method is a clone of the original ArtMethod: reflection may
            // report it as static even though it must be invoked with the original receiver.
            if (args.length == expected) {
                try {
                    return method.invoke(thisObject, args);
                } catch (IllegalArgumentException | NullPointerException first) {
                    XposedLog.log("backup receiver invoke failed, retrying: " + first);
                }
            }
            if (full.length == expected) {
                try {
                    return method.invoke(null, full);
                } catch (IllegalArgumentException | NullPointerException second) {
                    XposedLog.log("backup full invoke failed: " + second);
                    throw second;
                }
            }
            throw new IllegalArgumentException("backup instance arity " + expected
                    + " full=" + full.length + " args=" + args.length);
        }
        if (full.length == expected) {
            return method.invoke(null, full);
        }
        if (args.length == expected) {
            return method.invoke(null, args);
        }
        throw new IllegalArgumentException("backup static arity " + expected
                + " full=" + full.length + " args=" + args.length);
    }

    private Object invokeDirect(Object thisObject, Object[] args) throws Throwable {
        if (target instanceof Method) {
            Method method = (Method) target;
            method.setAccessible(true);
            return method.invoke(isStatic ? null : thisObject, args);
        }
        Constructor<?> constructor = (Constructor<?>) target;
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }
}
