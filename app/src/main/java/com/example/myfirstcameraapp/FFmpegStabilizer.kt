package com.example.myfirstcameraapp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

private const val TAG = "FFmpegStabilizer"

object FFmpegStabilizer {

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Main function you will call from MainActivity.
     */
    fun stabilize(
        context: Context,
        source: Uri,
        onSuccess: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                val ffmpegPath = installFfmpeg(context)
                if (ffmpegPath == null) {
                    onError("FFmpeg binary missing in assets")
                    return@execute
                }

                // Copy input to temp
                val inputFile = File(context.cacheDir, "input_${System.currentTimeMillis()}.mp4")
                context.contentResolver.openInputStream(source)?.use { inp ->
                    FileOutputStream(inputFile).use { out -> inp.copyTo(out) }
                }

                val outputFile = File(context.cacheDir, "stab_${System.currentTimeMillis()}.mp4")

                // Run ffmpeg
                val cmd = listOf(
                    ffmpegPath,
                    "-y",
                    "-i", inputFile.absolutePath,
                    "-vf", "deshake=rx=8:ry=8:edge=mirror",
                    "-preset", "veryfast",
                    "-c:v", "libx264",
                    outputFile.absolutePath
                )

                val process = ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()

                val log = process.inputStream.bufferedReader().readText()
                val code = process.waitFor()

                if (code != 0) {
                    onError("FFmpeg failed ($code):\n$log")
                    return@execute
                }

                // Save to gallery
                val savedUri = saveToGallery(context, outputFile)
                if (savedUri == null) {
                    onError("Failed to save stabilized file")
                } else {
                    onSuccess(savedUri)
                }

            } catch (e: Exception) {
                onError("Exception: ${e.message}")
            }
        }
    }

    /**
     * Copy ffmpeg from assets to internal storage, make it executable.
     */
    private fun installFfmpeg(context: Context): String? {
        return try {
            val inPath = "ffmpeg/armeabi-v7a/ffmpeg"
            val input = context.assets.open(inPath)

            val outDir = File(context.filesDir, "ffmpegbin")
            if (!outDir.exists()) outDir.mkdirs()

            val outFile = File(outDir, "ffmpeg")
            FileOutputStream(outFile).use { input.copyTo(it) }
            outFile.setExecutable(true)

            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "installFfmpeg: ${e.message}")
            null
        }
    }

    private fun saveToGallery(context: Context, file: File): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "stab_${System.currentTimeMillis()}.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyCameraApp")
                }
            }

            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: return null

            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { inp -> inp.copyTo(out) }
            }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "saveToGallery: ${e.message}")
            null
        }
    }
}
