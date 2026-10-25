package io.vt.container.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class GuestStore private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: GuestStore? = null

        fun get(context: Context): GuestStore {
            return instance ?: synchronized(this) {
                instance ?: GuestStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "vt_guests.json")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val _guests = MutableStateFlow<List<GuestRecord>>(emptyList())
    val guests: StateFlow<List<GuestRecord>> = _guests

    init {
        reload()
    }

    @Synchronized
    fun reload() {
        if (!file.isFile) {
            _guests.value = emptyList()
            return
        }
        _guests.value = try {
            val type = object : TypeToken<List<GuestRecord>>() {}.type
            gson.fromJson<List<GuestRecord>>(file.readText(), type)
                ?.filter { it.id.isNotBlank() && it.sourcePackage.isNotBlank() }
                ?.sortedBy { it.label }
                ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    @Synchronized
    private fun persist(list: List<GuestRecord>) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(gson.toJson(list))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
        _guests.value = list.sortedBy { it.label }
    }

    fun getById(id: String?): GuestRecord? = _guests.value.firstOrNull { it.id == id }

    fun findBySourcePackage(pkg: String): GuestRecord? =
        _guests.value.firstOrNull { it.sourcePackage == pkg }

    @Synchronized
    fun upsert(record: GuestRecord) {
        val current = _guests.value.toMutableList()
        val index = current.indexOfFirst { it.id == record.id }
        if (index >= 0) current[index] = record else current.add(record)
        persist(current)
    }

    @Synchronized
    fun remove(id: String) {
        persist(_guests.value.filterNot { it.id == id })
    }

    @Synchronized
    fun setEnabledModules(id: String, modules: Set<String>) {
        val current = _guests.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        current[index].enabledModules = modules.toMutableSet()
        persist(current)
    }

    @Synchronized
    fun setAutoUpdate(id: String, enabled: Boolean) {
        val current = _guests.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        current[index].autoUpdate = enabled
        persist(current)
    }

    fun guestRoot(guest: GuestRecord): File = File(File(appContext.filesDir, "guests"), guest.id)

    fun ensureGuestLayout(guest: GuestRecord) {
        val root = guestRoot(guest)
        File(root, "apk").mkdirs()
        File(root, "lib").mkdirs()
        File(root, "oat").mkdirs()
        val data = File(root, "data")
        for (name in listOf("files", "cache", "shared_prefs", "databases", "no_backup", "code_cache")) {
            File(data, name).mkdirs()
        }
    }
}
