package com.localmind.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.FileOutputStream

/**
 * Photo picking + on-disk avatar storage.
 *
 * Uses the **Photo Picker** (`ActivityResultContracts.PickVisualMedia`), not
 * `ACTION_GET_CONTENT`: on API 33+ it needs no runtime permission at all, and on older
 * versions it falls back to a system-provided picker. That means no permission prompt
 * and no `READ_MEDIA_IMAGES` dance on the happy path.
 *
 * Chosen images are copied into `filesDir/avatars/` and downscaled to [AVATAR_SIZE],
 * because a content:// URI is not durable — the user can revoke it, clear the media
 * store, or the file can simply move.
 */
object AvatarStore {

    /** Avatars never render larger than ~96dp, so 512px covers 5x density. */
    private const val AVATAR_SIZE = 512

    private fun dir(context: Context): File =
        File(context.filesDir, "avatars").apply { mkdirs() }

    /**
     * Copies [uri] into app storage as a square-ish JPEG and returns the absolute path,
     * or null when the image could not be read.
     */
    fun save(context: Context, uri: Uri, prefix: String): String? = runCatching {
        val source = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: return null

        // Centre-crop to a square so every avatar bubble is consistent without the
        // caller having to crop.
        val side = minOf(source.width, source.height)
        val cropped = if (source.width == source.height) {
            source
        } else {
            Bitmap.createBitmap(
                source,
                (source.width - side) / 2,
                (source.height - side) / 2,
                side,
                side,
            )
        }

        val scaled = if (side > AVATAR_SIZE) {
            Bitmap.createScaledBitmap(cropped, AVATAR_SIZE, AVATAR_SIZE, true)
        } else {
            cropped
        }

        val out = File(dir(context), "${prefix}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }

        // Free the intermediates; `scaled` may alias `cropped`/`source`.
        if (scaled !== cropped && !cropped.isRecycled) cropped.recycle()
        if (cropped !== source && !source.isRecycled) source.recycle()

        out.absolutePath
    }.getOrNull()

    /** Deletes an avatar that is no longer referenced. Best effort. */
    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).takeIf { it.exists() && it.parentFile?.name == "avatars" }?.delete() }
    }
}

/**
 * Remembers a photo-picker launcher.
 *
 * [onPicked] receives the stored absolute path, or is not called when the user backs
 * out of the picker.
 */
@Composable
fun rememberAvatarPicker(
    prefix: String,
    onPicked: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            AvatarStore.save(context, uri, prefix)?.let(onPicked)
        }
    }
    return remember(launcher, prefix) {
        {
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }
}
