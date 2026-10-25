package io.vt.xposed;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/** In-process log sink shared by the container and Xposed modules. */
public final class XposedLog {
    private static final String TAG = "VT-Xposed";
    private static final int MAX_ENTRIES = 800;
    private static final Deque<String> RING = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static volatile File logFile;

    private XposedLog() {
    }

    public static void init(File dir) {
        if (dir != null && !dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        logFile = dir == null ? null : new File(dir, "xposed.log");
    }

    public static void log(String message) {
        String line = TIME.format(new Date()) + "  " + message;
        Log.i(TAG, message);
        synchronized (RING) {
            RING.addLast(line);
            while (RING.size() > MAX_ENTRIES) {
                RING.removeFirst();
            }
        }
        File file = logFile;
        if (file != null) {
            try {
                if (file.length() > 4L * 1024 * 1024) {
                    File old = new File(file.getParentFile(), "xposed.log.1");
                    if (old.exists() && !old.delete()) {
                        // Keep writing to the current file rather than losing the new entry.
                    }
                    //noinspection ResultOfMethodCallIgnored
                    file.renameTo(old);
                }
                try (FileWriter writer = new FileWriter(file, true)) {
                    writer.write(line);
                    writer.write('\n');
                }
            } catch (IOException ignored) {
            }
        }
    }

    public static void log(Throwable throwable) {
        if (throwable == null) {
            return;
        }
        log(Log.getStackTraceString(throwable));
    }

    public static List<String> recent(int limit) {
        synchronized (RING) {
            List<String> all = new ArrayList<>(RING);
            if (limit <= 0 || all.size() <= limit) {
                return all;
            }
            return new ArrayList<>(all.subList(all.size() - limit, all.size()));
        }
    }

    public static File getLogFile() {
        return logFile;
    }
}
