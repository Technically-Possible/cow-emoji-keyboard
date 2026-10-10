package org.futo.inputmethod.latin.content

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.uix.actions.kitchen.KitchenSet
import org.futo.inputmethod.latin.uix.actions.fonttyper.ImageTyperState
import org.futo.inputmethod.latin.uix.actions.gif.GifFiles
import java.io.File
import java.util.UUID


val FONT_AUTHORITY = BuildConfig.APPLICATION_ID + ".font"
private val CODE_RENDERED_FONT_IMAGE = 1
private val CODE_COW_KITCHEN_IMAGE = 2
private val CODE_GIF = 3
private val CODE_KITCHEN_IMAGE = 4

private val URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
    addURI(FONT_AUTHORITY, "render/*", CODE_RENDERED_FONT_IMAGE)
    addURI(FONT_AUTHORITY, "cow/*", CODE_COW_KITCHEN_IMAGE)
    addURI(FONT_AUTHORITY, "gif/*", CODE_GIF)
    addURI(FONT_AUTHORITY, "kitchen/*/*", CODE_KITCHEN_IMAGE)
}

class KbContentProvider: ContentProvider() {
    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String?>?,
        selection: String?,
        selectionArgs: Array<out String?>?,
        sortOrder: String?
    ): Cursor? {
        return null
    }

    private val fileCache = HashMap<Uri, File>()
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if(fileCache.containsKey(uri)) return ParcelFileDescriptor.open(
            fileCache[uri],
            ParcelFileDescriptor.MODE_READ_ONLY
        )

        val id = uri.lastPathSegment ?: throw IllegalArgumentException("Invalid URI")
        return when(URI_MATCHER.match(uri)) {
            CODE_RENDERED_FONT_IMAGE -> ImageTyperState.renderRequest(context!!, UUID.fromString(id))
            // cow/<file> is from before there were other animals; images sent then still open
            CODE_COW_KITCHEN_IMAGE -> ParcelFileDescriptor.open(
                KitchenSet.of(context!!, "cow")!!.getCachedFile(context!!, id),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            CODE_KITCHEN_IMAGE -> ParcelFileDescriptor.open(
                (KitchenSet.of(context!!, uri.pathSegments[1]) ?: throw IllegalArgumentException("Unknown animal: $uri"))
                    .getCachedFile(context!!, id),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            CODE_GIF -> ParcelFileDescriptor.open(
                GifFiles.resolve(context!!, id),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            else -> throw IllegalArgumentException("Unsupported URI: $uri")
        }
    }


    override fun getType(uri: Uri): String =
        when(URI_MATCHER.match(uri)) {
            CODE_RENDERED_FONT_IMAGE -> "image/png"
            CODE_COW_KITCHEN_IMAGE -> "image/png"
            CODE_KITCHEN_IMAGE -> "image/png"
            CODE_GIF -> GifFiles.mimeTypeFor(uri.lastPathSegment ?: "")
            else -> throw IllegalArgumentException("Unsupported URI: $uri")
        }

    override fun insert(
        uri: Uri,
        values: ContentValues?
    ): Uri? = throw UnsupportedOperationException("Provider is read-only")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String?>?
    ): Int = throw UnsupportedOperationException("Provider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String?>?
    ): Int = throw UnsupportedOperationException("Provider is read-only")
}