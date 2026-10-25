package de.robv.android.xposed;

import android.content.SharedPreferences;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Read-only SharedPreferences bridge. Module private storage is not readable across UIDs on
 * modern Android, so VT looks for a mirrored copy under the container's private files first.
 */
public class XSharedPreferences implements SharedPreferences {
    private static volatile File mirrorBase;

    private final String packageName;
    private final String prefFileName;
    private final File directFile;
    private final Map<String, Object> values = new HashMap<>();
    private final List<OnSharedPreferenceChangeListener> listeners = new CopyOnWriteArrayList<>();
    private volatile long lastModified;
    private volatile boolean loaded;

    public XSharedPreferences(String packageName) {
        this(packageName, packageName + "_preferences");
    }

    public XSharedPreferences(String packageName, String prefFileName) {
        this.packageName = packageName;
        this.prefFileName = prefFileName;
        this.directFile = null;
    }

    public XSharedPreferences(File prefFile) {
        this.packageName = null;
        this.prefFileName = prefFile.getName();
        this.directFile = prefFile;
    }

    /** Must be called by the runtime with a container-wide preferences mirror directory. */
    public static void setMirrorBase(File dir) {
        mirrorBase = dir;
    }

    private File resolveFile() {
        if (directFile != null) {
            return directFile;
        }
        File base = mirrorBase;
        if (base != null && packageName != null) {
            return new File(new File(base, packageName), prefFileName + ".xml");
        }
        return packageName == null ? null
                : new File("/data/data/" + packageName + "/shared_prefs/" + prefFileName + ".xml");
    }

    public synchronized void startLoad() {
        reload();
    }

    public synchronized boolean hasFileChanged() {
        File file = resolveFile();
        return file != null && file.lastModified() != lastModified;
    }

    public boolean makeWorldReadable() {
        return false;
    }

    public File getFile() {
        return resolveFile();
    }

    @Override
    public synchronized Map<String, ?> getAll() {
        ensureLoaded();
        return Collections.unmodifiableMap(new HashMap<>(values));
    }

    @Override
    public String getString(String key, String defValue) {
        ensureLoaded();
        Object value = values.get(key);
        return value instanceof String ? (String) value : defValue;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key, Set<String> defValues) {
        ensureLoaded();
        Object value = values.get(key);
        if (value instanceof Set) {
            return new HashSet<>((Set<String>) value);
        }
        return defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        ensureLoaded();
        Object value = values.get(key);
        return value instanceof Integer ? (Integer) value : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        ensureLoaded();
        Object value = values.get(key);
        return value instanceof Long ? (Long) value : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        ensureLoaded();
        Object value = values.get(key);
        return value instanceof Float ? (Float) value : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        ensureLoaded();
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : defValue;
    }

    @Override
    public boolean contains(String key) {
        ensureLoaded();
        return values.containsKey(key);
    }

    @Override
    public Editor edit() {
        throw new UnsupportedOperationException("XSharedPreferences is read-only");
    }

    @Override
    public synchronized void registerOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
        listeners.add(listener);
    }

    @Override
    public synchronized void unregisterOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
        listeners.remove(listener);
    }

    public synchronized void reload() {
        File file = resolveFile();
        if (file == null || !file.isFile()) {
            values.clear();
            loaded = true;
            return;
        }
        lastModified = file.lastModified();
        Map<String, Object> parsed = new HashMap<>();
        try (FileInputStream input = new FileInputStream(file)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, "utf-8");
            int event;
            String currentKey = null;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("string".equals(tag)) {
                        currentKey = parser.getAttributeValue(null, "name");
                        parsed.put(currentKey, parser.nextText());
                    } else if ("boolean".equals(tag)) {
                        parsed.put(parser.getAttributeValue(null, "name"),
                                Boolean.parseBoolean(parser.getAttributeValue(null, "value")));
                    } else if ("int".equals(tag)) {
                        parsed.put(parser.getAttributeValue(null, "name"),
                                Integer.parseInt(parser.getAttributeValue(null, "value")));
                    } else if ("long".equals(tag)) {
                        parsed.put(parser.getAttributeValue(null, "name"),
                                Long.parseLong(parser.getAttributeValue(null, "value")));
                    } else if ("float".equals(tag)) {
                        parsed.put(parser.getAttributeValue(null, "name"),
                                Float.parseFloat(parser.getAttributeValue(null, "value")));
                    } else if ("set".equals(tag)) {
                        currentKey = parser.getAttributeValue(null, "name");
                        Set<String> set = new HashSet<>();
                        int depth = parser.getDepth();
                        while (parser.next() != XmlPullParser.END_DOCUMENT
                                && !(parser.getEventType() == XmlPullParser.END_TAG
                                && parser.getDepth() == depth)) {
                            if (parser.getEventType() == XmlPullParser.START_TAG
                                    && "string".equals(parser.getName())) {
                                set.add(parser.nextText());
                            }
                        }
                        parsed.put(currentKey, set);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        values.clear();
        values.putAll(parsed);
        loaded = true;
    }

    private void ensureLoaded() {
        if (!loaded) {
            reload();
        }
    }
}
