package com.example.myfirstcameraapp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.os.Handler
import android.os.Looper
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

private const val TAG = "FFmpegStabilizer"

object FFmpegStabilizer {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Stabilize the video (using the same deshake filter you had) and add a fixed center watermark "DEMO".
     *
     * @param context Android Context
     * @param source Uri of the input video (content Uri)
     * @param onSuccess callback with saved gallery Uri
     * @param onError callback with error message
     */
    fun stabilize(
        context: Context,
        source: Uri,
        onSuccess: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            var inputFile: File? = null
            var outputFile: File? = null
            try {
                // copy input Uri to a temporary file FFmpeg can access
                inputFile = File(context.cacheDir, "input_${System.currentTimeMillis()}.mp4")
                context.contentResolver.openInputStream(source)?.use { inp ->
                    FileOutputStream(inputFile).use { out -> inp.copyTo(out) }
                }

                outputFile = File(context.cacheDir, "stab_watermarked_${System.currentTimeMillis()}.mp4")

                // Keep the exact stabilization you had (deshake) and add drawtext (fixed center watermark "DEMO")
                val vfFilter = "deshake=rx=16:ry=16:edge=mirror," +
                        "drawtext=fontfile=/system/fonts/Roboto-Regular.ttf:" +
                        "text='DEMO':fontcolor=red:fontsize=80:" +
                        "x=(w-text_w)/2:y=(h-text_h)/2"

                // Build a single command string for FFmpegKit. Preserve encoder settings and copy audio.
                val ffmpegCmd = """
                    -y -i "${inputFile.absolutePath}" 
                    -vf "$vfFilter" 
                    -preset veryfast 
                    -c:v libx264 
                    -c:a copy 
                    "${outputFile.absolutePath}"
                """.trimIndent().replace("\n", " ")

                Log.d(TAG, "Running FFmpeg command: $ffmpegCmd")

                FFmpegKit.executeAsync(ffmpegCmd) { session ->
                    val returnCode = session.returnCode

                    if (ReturnCode.isSuccess(returnCode)) {
                        // Save to gallery
                        val savedUri = saveToGallery(context, outputFile)
                        if (savedUri != null) {
                            mainHandler.post { onSuccess(savedUri) }
                        } else {
                            mainHandler.post { onError("Failed to save stabilized file into gallery") }
                        }
                    } else {
                        val log = session.allLogsAsString ?: "No logs"
                        Log.e(TAG, "FFmpeg failed (code=${returnCode?.value}):\n$log")
                        val msg = "FFmpeg failed (code=${returnCode?.value}). See logcat for details."
                        mainHandler.post { onError(msg) }
                    }

                    // cleanup temporary files
                    try { inputFile?.delete() } catch (t: Throwable) { /* ignore */ }
                    try { outputFile?.delete() } catch (t: Throwable) { /* ignore */ }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in stabilize: ${e.message}", e)
                mainHandler.post { onError("Exception: ${e.localizedMessage}") }
                try { inputFile?.delete() } catch (t: Throwable) {}
                try { outputFile?.delete() } catch (t: Throwable) {}
            }
        }
    }

    /**
     * Save a file into the device Movies folder (MediaStore) and return its Uri, or null on failure.
     */
    private fun saveToGallery(context: Context, file: File?): Uri? {
        if (file == null || !file.exists()) return null
        return try {
            val displayName = "stab_${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.SIZE, file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyCameraApp")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: return null

            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { inp -> inp.copyTo(out) }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }

            uri
        } catch (e: Exception) {
            Log.e(TAG, "saveToGallery error: ${e.message}", e)
            null
        }
    }
}
