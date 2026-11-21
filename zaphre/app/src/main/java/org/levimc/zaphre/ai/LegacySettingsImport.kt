package org.levimc.zaphre.ai

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Imports an explicitly selected copy of the v3 SharedPreferences XML; never accesses v3 directly. */
object LegacySettingsImport {
    fun import(context: Context, uri: Uri): Int {
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                if (out.size() + n > 1024 * 1024) throw IOException("Settings file exceeds 1 MiB")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw IOException("Cannot open settings file")
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(bytes.inputStream(), "UTF-8")
        val strings = mutableMapOf<String, String>()
        val values = mutableMapOf<String, String>()
        var sawRoot = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.DOCDECL || event == XmlPullParser.ENTITY_REF) throw IOException("Unsupported XML declaration")
            if (event == XmlPullParser.START_TAG) {
                if (parser.depth == 1) {
                    if (parser.name != "map") throw IOException("Not a v3 settings XML file")
                    sawRoot = true
                } else if (parser.depth == 2) {
                    val name = parser.getAttributeValue(null, "name") ?: throw IOException("Invalid settings entry")
                    if (name.length > 200) throw IOException("Invalid settings entry")
                    when (parser.name) {
                        "string" -> strings[name] = parser.nextText().take(65536)
                        "int", "float", "boolean" -> values[name] = parser.getAttributeValue(null, "value") ?: ""
                    }
                }
            }
            event = parser.next()
        }
        if (!sawRoot) throw IOException("Not a v3 settings XML file")
        val prefs = context.getSharedPreferences("zaphre_ai_settings", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        var count = 0
        for (preset in BUILTIN_PROVIDERS) {
            val pid = preset.id
            val names = listOf("key_$pid", "url_$pid", "model_$pid", "tool_mode_$pid")
            // Never combine a new account key with a pre-existing provider endpoint.
            if (names.any { prefs.contains(it) }) continue
            if (names.none { strings.containsKey(it) }) continue
            for (name in names) strings[name]?.let { edit.putString(name, it) }
            count++
        }
        val active = strings["active_provider"]
        if (!prefs.contains("active_provider") && BUILTIN_PROVIDERS.any { it.id == active }) edit.putString("active_provider", active)
        if (!prefs.contains("custom_prompt")) strings["custom_prompt"]?.let { edit.putString("custom_prompt", it) }
        if (!prefs.contains("max_steps")) values["max_steps"]?.toIntOrNull()?.let { edit.putInt("max_steps", it.coerceIn(3, 80)) }
        if (!prefs.contains("temperature")) values["temperature"]?.toFloatOrNull()?.takeIf { it.isFinite() }?.let { edit.putFloat("temperature", it.coerceIn(0f, 1.5f)) }
        if (!prefs.contains("auto_stop_after_loop")) values["auto_stop_after_loop"]?.let { if (it == "true" || it == "false") edit.putBoolean("auto_stop_after_loop", it == "true") }
        if (!edit.commit()) throw IOException("Could not save imported settings")
        return count
    }
}
