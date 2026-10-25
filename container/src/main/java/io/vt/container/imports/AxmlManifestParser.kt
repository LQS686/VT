package io.vt.container.imports

import io.vt.container.data.StoredReceiverInfo
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipFile

/**
 * Minimal Android binary-XML reader used to recover <receiver> intent-filter actions,
 * which PackageManager does not expose through PackageInfo.
 */
object AxmlManifestParser {
    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML = 0x0003
    private const val RES_XML_START_ELEMENT = 0x0102
    private const val RES_XML_END_ELEMENT = 0x0103
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_BOOLEAN = 0x12
    private const val TYPE_FIRST_COLOR_INT = 0x1c
    private const val TYPE_LAST_COLOR_INT = 0x1f

    fun parseReceivers(apkPath: String): MutableList<StoredReceiverInfo> {
        val bytes = runCatching {
            ZipFile(File(apkPath)).use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml") ?: return mutableListOf<StoredReceiverInfo>()
                zip.getInputStream(entry).use { it.readBytes() }
            }
        }.getOrNull() ?: return mutableListOf()
        return runCatching<MutableList<StoredReceiverInfo>> {
            Parser(bytes).parse().toMutableList()
        }.getOrElse { mutableListOf() }
    }

    private class Parser(private val data: ByteArray) {
        private val strings = ArrayList<String>()
        private var utf8 = false
        private val receivers = ArrayList<StoredReceiverInfo>()
        private var current: StoredReceiverInfo? = null
        private var inFilter = false

        fun parse(): List<StoredReceiverInfo> {
            if (data.size < 8) return emptyList()
            if (data[0] == '<'.code.toByte()) return parseTextManifest()
            var offset = if (u16(0) == RES_XML) u16(2) else 0
            while (offset + 8 <= data.size) {
                val type = u16(offset)
                val headerSize = u16(offset + 2)
                val size = u32(offset + 4)
                if (size <= 0 || offset + size > data.size) break
                when (type) {
                    RES_STRING_POOL -> parseStringPool(offset, headerSize, size)
                    RES_XML -> Unit
                    RES_XML_START_ELEMENT -> parseStart(offset, headerSize, size)
                    RES_XML_END_ELEMENT -> parseEnd(offset, headerSize, size)
                }
                offset += size
            }
            return receivers
        }

        private fun parseStringPool(offset: Int, headerSize: Int, size: Int) {
            if (offset + 28 > data.size) return
            val count = u32(offset + 8)
            val flags = u32(offset + 16)
            utf8 = flags and 0x100 != 0
            val stringsStart = u32(offset + 20)
            val offsetsBase = offset + headerSize
            strings.clear()
            for (i in 0 until count) {
                val stringOffset = u32(offsetsBase + i * 4)
                strings.add(readString(offset + stringsStart + stringOffset))
            }
        }

        private fun readString(start: Int): String {
            if (start < 0 || start >= data.size) return ""
            return try {
                if (utf8) {
                    var pos = start
                    var len = data[pos].toInt() and 0xff
                    if (len and 0x80 != 0) {
                        len = ((len and 0x7f) shl 8) or (data[pos + 1].toInt() and 0xff)
                        pos += 2
                    } else {
                        pos += 1
                    }
                    String(data, pos, len, Charsets.UTF_8)
                } else {
                    val len = u16(start)
                    val bytes = ByteArray(len * 2)
                    System.arraycopy(data, start + 2, bytes, 0, len * 2)
                    String(bytes, Charset.forName("UTF-16LE"))
                }
            } catch (_: Throwable) {
                ""
            }
        }

        private fun parseStart(offset: Int, headerSize: Int, size: Int) {
            val ext = offset + headerSize
            if (ext + 20 > data.size) return
            val name = string(u32(ext + 4))
            val attrStart = u16(ext + 8)
            val attrSize = u16(ext + 10)
            val attrCount = u16(ext + 12)
            val attrsBase = ext + attrStart
            val attrs = HashMap<String, String>()
            val bools = HashMap<String, Boolean>()
            for (i in 0 until attrCount) {
                val pos = attrsBase + i * attrSize
                if (pos + 20 > data.size) break
                val attrName = string(u32(pos + 4))
                val raw = u32(pos + 8)
                val dataType = data[pos + 15].toInt() and 0xff
                val valueIndex = u32(pos + 16)
                val value = if (raw >= 0) string(raw) else if (dataType == TYPE_STRING) string(valueIndex) else ""
                attrs[attrName] = value
                if (dataType == TYPE_INT_BOOLEAN) bools[attrName] = valueIndex != 0
            }
            when (name) {
                "receiver" -> {
                    current = StoredReceiverInfo(
                        name = attrs["name"].orEmpty(),
                        exported = bools["exported"] ?: false,
                        processName = attrs["process"]?.takeIf { it.isNotBlank() },
                        permission = attrs["permission"]?.takeIf { it.isNotBlank() }
                    )
                    inFilter = false
                }
                "intent-filter" -> if (current != null) inFilter = true
                "action" -> if (current != null && inFilter) {
                    attrs["name"]?.takeIf { it.isNotBlank() }?.let { current!!.actions.add(it) }
                }
                "category" -> if (current != null && inFilter) {
                    attrs["name"]?.takeIf { it.isNotBlank() }?.let { current!!.categories.add(it) }
                }
            }
        }

        private fun parseEnd(offset: Int, headerSize: Int, size: Int) {
            val ext = offset + headerSize
            if (ext + 8 > data.size) return
            val name = string(u32(ext + 4))
            if (name == "receiver") {
                current?.takeIf { it.name.isNotBlank() && it.actions.isNotEmpty() }?.let { receivers.add(it) }
                current = null
                inFilter = false
            } else if (name == "intent-filter") {
                inFilter = false
            }
        }

        private fun parseTextManifest(): List<StoredReceiverInfo> {
            return try {
                val text = String(data, Charsets.UTF_8)
                val parser = android.util.Xml.newPullParser()
                parser.setInput(text.reader())
                var event = parser.eventType
                var receiver: StoredReceiverInfo? = null
                var filter = false
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "receiver" -> receiver = StoredReceiverInfo(
                                name = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name").orEmpty(),
                                exported = parser.getAttributeValue(
                                    "http://schemas.android.com/apk/res/android", "exported"
                                )?.toBoolean() ?: false,
                                processName = parser.getAttributeValue(
                                    "http://schemas.android.com/apk/res/android", "process"
                                )
                            )
                            "intent-filter" -> filter = true
                            "action" -> if (receiver != null && filter) {
                                parser.getAttributeValue(
                                    "http://schemas.android.com/apk/res/android", "name"
                                )?.let { receiver!!.actions.add(it) }
                            }
                        }
                    } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG) {
                        if (parser.name == "receiver") {
                            receiver?.takeIf { it.actions.isNotEmpty() }?.let { receivers.add(it) }
                            receiver = null
                        } else if (parser.name == "intent-filter") {
                            filter = false
                        }
                    }
                    event = parser.next()
                }
                receivers
            } catch (_: Throwable) {
                emptyList()
            }
        }

        private fun string(index: Int): String =
            if (index in strings.indices) strings[index] else ""

        private fun u16(offset: Int): Int =
            (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

        private fun u32(offset: Int): Int =
            (data[offset].toInt() and 0xff) or
                ((data[offset + 1].toInt() and 0xff) shl 8) or
                ((data[offset + 2].toInt() and 0xff) shl 16) or
                ((data[offset + 3].toInt() and 0xff) shl 24)
    }
}
