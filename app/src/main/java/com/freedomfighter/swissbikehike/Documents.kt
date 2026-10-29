package com.freedomfighter.swissbikehike

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore

/** Files the person keeps: saved under Documents/Swiss Bikers and Pedestrians Map through MediaStore, then offered for sharing. */
object Documents {
    const val FOLDER = "Documents/Swiss Bikers and Pedestrians Map"

    fun save(ctx: Context, name: String, mime: String, bytes: ByteArray): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
        return uri
    }

    fun share(ctx: Context, uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
    }
}
