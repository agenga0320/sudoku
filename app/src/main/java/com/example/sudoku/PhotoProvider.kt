package com.example.sudoku

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Hands the camera app a file in the app's cache to save a photo into, and the chat page
 * the same file to upload. Not exported: other apps reach it only through a granted URI.
 */
class PhotoProvider : ContentProvider() {

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(context!!, uri), ParcelFileDescriptor.parseMode(mode))

    override fun getType(uri: Uri) = "image/jpeg"

    // Pages name and size the upload from these.
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor {
        val file = file(context!!, uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>(file.name, file.length()))
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0

    companion object {
        /** A new, empty file for the next photo; earlier photos are removed. */
        fun newPhoto(context: Context): File {
            val dir = File(context.cacheDir, "photos")
            dir.deleteRecursively()
            dir.mkdirs()
            return File(dir, "puzzle-${System.currentTimeMillis()}.jpg")
        }

        /** True if the camera saved a photo at [uri]. */
        fun newPhotoTaken(context: Context, uri: Uri) = file(context, uri).length() > 0

        fun uri(context: Context, file: File): Uri = Uri.parse("content://${context.packageName}.photos/${file.name}")

        private fun file(context: Context, uri: Uri) =
            File(File(context.cacheDir, "photos"), uri.lastPathSegment.orEmpty().substringAfterLast('/'))
    }
}
