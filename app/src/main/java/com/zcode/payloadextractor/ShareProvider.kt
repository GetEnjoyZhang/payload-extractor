package com.zcode.payloadextractor

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/** 无 androidx 依赖的极简文件分享 Provider（file -> content://）。 */
class ShareProvider : ContentProvider() {

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw IllegalStateException("no context")
        val name = uri.lastPathSegment ?: throw IllegalArgumentException("空路径")
        require(!name.contains("..")) { "非法路径" }
        val dir = File(ctx.getExternalFilesDir(null), "extracted")
        return ParcelFileDescriptor.open(File(dir, name), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
}
