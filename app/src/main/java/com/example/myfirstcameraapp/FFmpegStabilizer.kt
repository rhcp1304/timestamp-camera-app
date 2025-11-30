package com.example.myfirstcameraapp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

private const val TAG = "FFmpegStabilizer"

object FFmpegStabilizer {

    private val executor = Executors.newSingleThreadExecutor()

    fun stabilize(
        context: Context,
        source: Uri,
        onSuccess: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {

        executor.execute {
            try {
                // Copy input video to local temp
                val inputFile = File(context.cacheDir, "input_${System.currentTimeMillis()}.mp4")
                context.contentResolver.openInputStream(source)?.use { input ->
                    FileOutputStream(inputFile).use { output -> input.copyTo(output) }
                }

                val outputFile = File(context.cacheDir, "stab_${System.currentTimeMillis()}.mp4")

                val ffmpegCmd = "-y -i ${inputFile.absolutePath} " +
                        "-vf deshake=rx=8:ry=8:edge=mirror " +
                        "-preset veryfast -c:v libx264 " +
                        outputFile.absolutePath

                Log.d(TAG, "FFmpeg Command: $ffmpegCmd")

                FFmpegKit.executeAsync(ffmpegCmd) { session ->
                    val returnCode = session.returnCode

                    if (ReturnCode.isSuccess(returnCode)) {
                        val savedUri = saveToGallery(context, outputFile)
                        if (savedUri != null) {
                            onSuccess(savedUri)
                        } else {
                            onError("Failed to save stabilized file into gallery")
                        }
                    } else {
                        val failLog = session.allLogsAsString
                        onError("Stabilization failed: $failLog")
                    }
                }

            } catch (e: Exception) {
                onError("Exception: ${e.message}")
                e.printStackTrace()
            }
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
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null

            context.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }

            uri
        } catch (e: Exception) {
            Log.e(TAG, "saveToGallery: ${e.message}")
            null
        }
    }
}
