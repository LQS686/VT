package io.vt.xposed;

public final class ModuleSpec {
    public final String packageName;
    public final String apkPath;
    public final String label;

    public ModuleSpec(String packageName, String apkPath, String label) {
        this.packageName = packageName;
        this.apkPath = apkPath;
        this.label = label == null || label.isEmpty() ? packageName : label;
    }
}
