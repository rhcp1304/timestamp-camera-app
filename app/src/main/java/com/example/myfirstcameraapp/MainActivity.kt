package com.example.myfirstcameraapp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            if (perms.all { it.value }) {
                setContent { CameraScreen() }
            } else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val hasAllPerms = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (hasAllPerms) {
            setContent { CameraScreen() }
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var currentRecording by remember { mutableStateOf<Recording?>(null) }
    var isRecording by remember { mutableStateOf(false) }

    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    Box(modifier = Modifier.fillMaxSize()) {

        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            lifecycleOwner = lifecycleOwner,
            cameraExecutor = cameraExecutor,
            onVideoCaptureReady = { vc -> videoCapture = vc }
        )

        // Record button
        Button(
            onClick = {
                if (isRecording) {
                    currentRecording?.stop()
                    currentRecording = null
                    isRecording = false
                } else {
                    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(System.currentTimeMillis())

                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    }

                    val outputOptions = MediaStoreOutputOptions.Builder(
                        context.contentResolver,
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    )
                        .setContentValues(cv)
                        .build()

                    currentRecording = videoCapture!!
                        .output
                        .prepareRecording(context, outputOptions)
                        .withAudioEnabled()
                        .start(ContextCompat.getMainExecutor(context)) { event ->
                            when (event) {
                                is VideoRecordEvent.Start -> {
                                    isRecording = true
                                }

                                is VideoRecordEvent.Finalize -> {
                                    isRecording = false
                                    currentRecording = null

                                    if (!event.hasError()) {
                                        val uri = event.outputResults.outputUri

                                        Toast.makeText(
                                            context,
                                            "Video saved. Stabilizing...",
                                            Toast.LENGTH_SHORT
                                        ).show()

                                        // ================================
                                        // 🔥 RUN FFmpeg STABILIZATION HERE
                                        // ================================
                                        FFmpegStabilizer.stabilize(
                                            context = context,
                                            source = uri,
                                            onSuccess = { stabUri ->
                                                Toast.makeText(
                                                    context,
                                                    "Stabilized saved!",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                                Log.d(TAG, "Stabilized URI = $stabUri")
                                            },
                                            onError = { err ->
                                                Toast.makeText(
                                                    context,
                                                    "FFmpeg failed: $err",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                                Log.e(TAG, "Stabilization error: $err")
                                            }
                                        )

                                    } else {
                                        Log.e(TAG, "Recording error: ${event.error}")
                                    }
                                }
                            }
                        }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
        ) {
            Text(if (isRecording) "STOP" else "RECORD")
        }
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    cameraExecutor: ExecutorService,
    onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit
) {
    val context = LocalContext.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->

            val previewView = PreviewView(ctx)

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({

                val cameraProvider = cameraProviderFuture.get()

                // Camera Preview UseCase
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                // Recorder + VideoCapture UseCase
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                    .build()

                val videoBuilder = VideoCapture.Builder(recorder)

                // Enable Camera2 video stabilization
                val extender = Camera2Interop.Extender(videoBuilder)
                extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                )

                val videoCapture = videoBuilder.build()
                onVideoCaptureReady(videoCapture)

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture
                )

            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}
