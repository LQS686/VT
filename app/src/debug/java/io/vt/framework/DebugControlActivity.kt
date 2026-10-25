package io.vt.framework

import android.app.Activity
import android.os.Bundle
import io.vt.container.GuestLauncher
import io.vt.container.data.GuestStore
import io.vt.container.data.LaunchOutcome
import io.vt.container.data.ModuleStore
import io.vt.container.imports.GuestImporter
import io.vt.xposed.XposedLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Debug-only command bridge used by automated device tests. */
class DebugControlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra("action").orEmpty()
        val pkg = intent.getStringExtra("pkg").orEmpty()
        val module = intent.getStringExtra("module").orEmpty()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = GuestStore.get(this@DebugControlActivity)
                val result = when (action) {
                    "import" -> {
                        val importer = GuestImporter(this@DebugControlActivity, store)
                        importer.importInstalled(pkg) { XposedLog.log("debug import: $it") }
                            ?.let { "imported ${it.label} ${it.apkFiles.size}" } ?: "import failed"
                    }
                    "modules" -> {
                        val modules = ModuleStore.get(this@DebugControlActivity).refresh()
                        "modules=${modules.size}: " + modules.joinToString { it.packageName }
                    }
                    "enable" -> {
                        ModuleStore.get(this@DebugControlActivity).refresh()
                        val guest = store.findBySourcePackage(pkg) ?: store.getById(pkg)
                        if (guest == null) "no guest $pkg" else {
                            val enabled = guest.enabledModules.toMutableSet()
                            enabled.add(module)
                            store.setEnabledModules(guest.id, enabled)
                            "enabled=$module for ${guest.label}"
                        }
                    }
                    "update" -> {
                        val guest = store.findBySourcePackage(pkg) ?: store.getById(pkg)
                        if (guest == null) "no guest $pkg" else {
                            val updated = GuestImporter(this@DebugControlActivity, store)
                                .updateFromDevice(guest)
                            if (updated == null) "no update for ${guest.label}" else
                                "updated ${guest.label} ${guest.versionName} -> ${updated.versionName}"
                        }
                    }
                    "disable" -> {
                        val guest = store.findBySourcePackage(pkg) ?: store.getById(pkg)
                        if (guest == null) "no guest $pkg" else {
                            val enabled = guest.enabledModules.toMutableSet()
                            enabled.remove(module)
                            store.setEnabledModules(guest.id, enabled)
                            "disabled=$module for ${guest.label}"
                        }
                    }
                    "stop" -> {
                        val guest = store.findBySourcePackage(pkg) ?: store.getById(pkg)
                        if (guest == null) "no guest $pkg" else {
                            GuestLauncher(this@DebugControlActivity, store).stop(guest)
                            "stopped ${guest.label}"
                        }
                    }
                    "clear" -> {
                        val guest = store.findBySourcePackage(pkg) ?: store.getById(pkg)
                        if (guest == null) "no guest $pkg" else {
                            GuestLauncher(this@DebugControlActivity, store).stop(guest)
                            Thread.sleep(800)
                            java.io.File(guest.dataDir).deleteRecursively()
                            store.ensureGuestLayout(guest)
                            "cleared ${guest.label}"
                        }
                    }
                    "launch" -> {
                        val guest = store.findBySourcePackage(pkg) ?: store.getById(pkg)
                        val activityClass = intent.getStringExtra("class")
                        if (guest == null) "no guest $pkg" else {
                            when (val outcome = GuestLauncher(this@DebugControlActivity, store)
                                .launch(guest, activityClass)) {
                                is LaunchOutcome.Success -> "launched ${guest.label} modules=${guest.enabledModules}"
                                is LaunchOutcome.Failure -> "launch failed: ${outcome.message}"
                            }
                        }
                    }
                    else -> {
                        val guests = store.guests.value
                        "guests=${guests.size}: " + guests.joinToString { "${it.sourcePackage}(${it.enabledModules},v${it.versionName})" }
                    }
                }
                XposedLog.log("DEBUG $action -> $result")
            } catch (t: Throwable) {
                XposedLog.log("DEBUG $action failed")
                XposedLog.log(t)
            } finally {
                runOnUiThread { finish() }
            }
        }
    }
}
