package android.content.res;

import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LayoutInflated;

/**
 * Minimal XResources compatibility layer. String replacement is applied when modules query
 * through this instance; full runtime overlay (layouts/drawables) is not implemented yet.
 */
public class XResources extends Resources {
    private static final Map<Integer, Object> ID_REPLACEMENTS = new ConcurrentHashMap<>();
    private static final Map<String, Object> NAME_REPLACEMENTS = new ConcurrentHashMap<>();
    private static final Map<Integer, XC_LayoutInflated> LAYOUT_HOOKS = new ConcurrentHashMap<>();

    private final String resDir;

    public XResources(AssetManager assets, DisplayMetrics metrics, Configuration config) {
        super(assets, metrics, config);
        this.resDir = "";
    }

    public static class ResourceNames {
        public final int id;
        public final String pkg;
        public final String name;
        public final String type;
        public final String fullName;

        private ResourceNames(int id, String pkg, String name, String type) {
            this.id = id;
            this.pkg = pkg;
            this.name = name;
            this.type = type;
            this.fullName = pkg + ":" + type + "/" + name;
        }
    }

    private static String key(String type, String name) {
        return type + "/" + name;
    }

    public void setReplacement(int id, Object replacement) {
        if (replacement == null) {
            ID_REPLACEMENTS.remove(id);
        } else {
            ID_REPLACEMENTS.put(id, replacement);
        }
    }

    public void setReplacement(String fullName, Object replacement) {
        if (fullName == null) {
            return;
        }
        if (replacement == null) {
            NAME_REPLACEMENTS.remove(fullName);
        } else {
            NAME_REPLACEMENTS.put(fullName, replacement);
        }
    }

    public void setReplacement(String pkg, String type, String name, Object replacement) {
        setReplacement(type + "/" + name, replacement);
    }

    public XC_LayoutInflated.Unhook hookLayout(int id, XC_LayoutInflated callback) {
        LAYOUT_HOOKS.put(id, callback);
        return callback.new Unhook(resDir, id);
    }

    public XC_LayoutInflated.Unhook hookLayout(String fullName, XC_LayoutInflated callback) {
        int id = 0;
        try {
            id = getIdentifier(fullName, null, null);
        } catch (Throwable ignored) {
        }
        return hookLayout(id, callback);
    }

    public XC_LayoutInflated.Unhook hookLayout(String pkg, String type, String name,
                                               XC_LayoutInflated callback) {
        return hookLayout(type + "/" + name, callback);
    }

    public static void unhookLayout(String resDir, int id, XC_LayoutInflated callback) {
        LAYOUT_HOOKS.remove(id, callback);
    }

    public static XC_LayoutInflated getLayoutHook(int id) {
        return LAYOUT_HOOKS.get(id);
    }

    private Object findReplacement(int id) {
        Object direct = ID_REPLACEMENTS.get(id);
        if (direct != null) {
            return direct;
        }
        try {
            String type = getResourceTypeName(id);
            String name = getResourceEntryName(id);
            return NAME_REPLACEMENTS.get(key(type, name));
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public String getString(int id) throws NotFoundException {
        Object replacement = findReplacement(id);
        if (replacement instanceof String) {
            return (String) replacement;
        }
        return super.getString(id);
    }

    @Override
    public CharSequence getText(int id) throws NotFoundException {
        Object replacement = findReplacement(id);
        if (replacement instanceof CharSequence) {
            return (CharSequence) replacement;
        }
        return super.getText(id);
    }

    @Override
    public Drawable getDrawable(int id) throws NotFoundException {
        Object replacement = findReplacement(id);
        if (replacement instanceof Drawable) {
            return (Drawable) replacement;
        }
        return super.getDrawable(id);
    }
}
