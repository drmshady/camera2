package com.example.cameraxapp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.cameraxapp.databinding.ActivityCaptureBinding
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalCamera2Interop::class)
class CaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptureBinding

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var camera: Camera? = null // Store the Camera object

    // Loaded calibration values
    private var iso = 100
    private var shutterSpeed = 10_000_000L // 10ms
    private var focusDistance = 0f
    private var fps = 30

    @Volatile private var awbLockedApplied = false

    private var isBurstMode = false
    private val burstHandler = Handler(Looper.getMainLooper())
    private val burstRunnable = object : Runnable {
        override fun run() {
            takePhoto(inBurst = true)
            if (isBurstMode) {
                burstHandler.postDelayed(this, 500) // 2 photos per second
            }
        }
    }

    companion object {
        private const val TAG = "CaptureActivity"
        private const val DATE_FORMAT = "yyyyMMdd_HHmmss"

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadCalibration()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
        }

        binding.btnRecordVideo.setOnClickListener { toggleVideo() }

        binding.btnTakePhoto.setOnClickListener {
            if (isBurstMode) stopBurst() else takePhoto()
        }
        binding.btnTakePhoto.setOnLongClickListener {
            startBurst()
            true
        }

        binding.swTorch.setOnCheckedChangeListener { _, isChecked ->
            camera?.cameraControl?.enableTorch(isChecked)
        }
    }

    private fun loadCalibration() {
        val cal = getSharedPreferences("CalibrationData", Context.MODE_PRIVATE)
        iso = cal.getInt("iso", 100) // Start with safe, non-zero defaults
        shutterSpeed = cal.getLong("shutterSpeed", 10_000_000L)
        focusDistance = cal.getFloat("focusDistance", 0f)
        fps = cal.getInt("fps", 30)

        val focusCm = if (focusDistance <= 0f) Double.POSITIVE_INFINITY else 100.0 / focusDistance
        val focusText = if (focusCm.isInfinite()) "Focus: ∞" else "Focus: %.1f cm".format(focusCm)
        binding.tvCaptureData.text = "ISO: $iso\nShutter(ns): $shutterSpeed\n$focusText\nFPS: $fps"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (awbLockedApplied) return
                    val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)
                    if (awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED || awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED) {
                        awbLockedApplied = true
                        applyAwbLockOnly()
                    }
                }
            }

            val preview = Preview.Builder().also {
                Camera2Interop.Extender(it).setSessionCaptureCallback(captureCallback)
            }.build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

            imageCapture = ImageCapture.Builder().build()
            videoCapture = VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build())

            provider.unbindAll()
            this.camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, videoCapture)

            // --- CRITICAL FIX SEQUENCE ---
            val cam = this.camera ?: return@addListener
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val c2Control = Camera2CameraControl.from(cam.cameraControl)

            // 1. Read hardware capabilities
            val isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureRangeNs = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val fpsRanges = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()

            // 2. Choose a valid FPS range
            val chosenRange = chooseBestFpsRange(fpsRanges, fps) ?: fpsRanges.firstOrNull() ?: Range(fps, fps)
            val frameDurationNs = 1_000_000_000L / chosenRange.upper.coerceAtLeast(1)
            val maxVideoExposureNs = frameDurationNs - 1_000_000L

            // 3. CLAMP loaded calibration values against hardware support
            val safeIso = isoRange?.let { iso.coerceIn(it.lower, it.upper) } ?: iso
            val safeShutter = exposureRangeNs?.let { shutterSpeed.coerceIn(it.lower, maxVideoExposureNs) } ?: shutterSpeed

            Log.d(TAG, "Clamped Shutter from $shutterSpeed to $safeShutter")
            Log.d(TAG, "Clamped ISO from $iso to $safeIso")

            // 4. Build and apply the SAFE request
            val opts = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, safeIso)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, safeShutter)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chosenRange)
                .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
                .build()

            c2Control.setCaptureRequestOptions(opts)

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto(inBurst: Boolean = false) {
        val imageCapture = this.imageCapture ?: return

        val name = SimpleDateFormat(DATE_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraXApp")
            }
        }

        val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (imageUri == null) {
            Log.e(TAG, "Failed to create MediaStore entry for image.")
            return
        }

        val outputStream: OutputStream? = try {
            contentResolver.openOutputStream(imageUri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open output stream", e)
            return
        }

        if (outputStream == null) {
            Log.e(TAG, "Output stream is null.")
            return
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputStream).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (!inBurst) {
                        Toast.makeText(baseContext, "Photo capture succeeded", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun startBurst() {
        isBurstMode = true
        binding.btnTakePhoto.text = "Stop Burst"
        burstHandler.post(burstRunnable)
    }

    private fun stopBurst() {
        isBurstMode = false
        binding.btnTakePhoto.text = "Take Photo"
        burstHandler.removeCallbacks(burstRunnable)
    }

    private fun applyAwbLockOnly() {
        camera?.let { cam ->
            val c2Control = Camera2CameraControl.from(cam.cameraControl)
            val opts = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                .build()
            c2Control.setCaptureRequestOptions(opts)
        } ?: Log.e(TAG, "Cannot apply AWB lock, camera is null")
    }

    private fun chooseBestFpsRange(ranges: Array<Range<Int>>, targetFps: Int): Range<Int>? {
        if (ranges.isEmpty()) return null
        val containing = ranges.filter { it.lower <= targetFps && targetFps <= it.upper }.minByOrNull { it.upper - it.lower }
        return containing ?: ranges.minByOrNull { abs(it.upper - targetFps) }
    }

    private fun toggleVideo() {
        val vc = videoCapture ?: return
        activeRecording?.let {
            it.stop()
            activeRecording = null
            binding.btnRecordVideo.text = "Record Video"
            return
        }

        val baseName = SimpleDateFormat(DATE_FORMAT, Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraXApp")
            }
        }

        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()

        activeRecording = vc.output.prepareRecording(this, output)
            .apply { if (allPermissionsGranted()) withAudioEnabled() }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> binding.btnRecordVideo.text = "Stop Recording"
                    is VideoRecordEvent.Finalize -> {
                        binding.btnRecordVideo.text = "Record Video"
                        activeRecording = null
                        if (!event.hasError()) {
                            Toast.makeText(baseContext, "Video capture succeeded: ${event.outputResults.outputUri}", Toast.LENGTH_SHORT).show()
                        } else {
                            Log.e(TAG, "Video capture ended with error: ${event.error}")
                        }
                    }
                }
            }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
