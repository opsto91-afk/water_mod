package org.levimc.zaphre.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.util.UUID

/** User-selected backup directory; does not replace the RE runtime workspace. */
object BackupFolder {
    private const val PREFS = "peta_backup_folder"
    fun selected(context: Context): Uri? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("tree", null)?.let(Uri::parse)
    fun choose(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val tree = DocumentFile.fromTreeUri(context, uri) ?: throw IOException("Cannot open selected folder")
        if (!tree.canWrite()) throw IOException("Selected folder is not writable")
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("tree", uri.toString()).commit())
    }
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("tree").apply()
    }
    fun writeNew(context: Context, prefix: String, text: String): String {
        val treeUri = selected(context) ?: throw IOException("No backup folder selected")
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Selected folder unavailable; select it again")
        if (!tree.canWrite()) throw IOException("Selected folder is no longer writable; select it again")
        val name = "${prefix}-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.txt"
        val doc = tree.createFile("text/plain", name) ?: throw IOException("Could not create backup file")
        try {
            val stream = context.contentResolver.openOutputStream(doc.uri, "w") ?: throw IOException("Cannot write backup")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        } catch (e: Exception) { doc.delete(); throw e }
        return name
    }
}
