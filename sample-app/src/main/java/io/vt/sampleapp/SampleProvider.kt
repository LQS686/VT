package io.vt.sampleapp

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.io.File

class SampleProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        SampleApp.providerInitialized = true
        context?.let { File(it.filesDir, "provider_created").writeText("yes") }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        return MatrixCursor(arrayOf("value")).apply { addRow(arrayOf("provider-ok")) }
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.item/vt-ping"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
