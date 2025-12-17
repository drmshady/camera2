package com.example.cameraxapp

import android.Manifest
import android.content.ContentValues
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
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.widget.SeekBar
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
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.cameraxapp.databinding.ActivityMainBinding
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    // Camera2 characteristics
    private var isoRange: Range<Int>? = null
    private var exposureRangeNs: Range<Long>? = null
    private var minFocusDistance: Float? = null
    private var hasManualSensor: Boolean = false
    private var isAwbLockSupported: Boolean = false
    private var fpsRanges: Array<Range<Int>>? = null

    // Live readback (actual applied values)
    private var lastIso: Int? = null
    private var lastExposureNs: Long? = null
    private var lastFocusDpt: Float? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPhoto.setOnClickListener { takePhoto() }
        binding.btnVideo.setOnClickListener { toggleVideo() }

        val seekListener = SimpleSeek { applyCamera2Controls() }
        binding.sbFocus.setOnSeekBarChangeListener(seekListener)
        binding.sbIso.setOnSeekBarChangeListener(seekListener)
        binding.sbShutter.setOnSeekBarChangeListener(seekListener)

        binding.swPhotogrammetryLock.setOnCheckedChangeListener { _, _ -> applyCamera2Controls() }

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQ_PERMS)
    }

    // ---------------- Camera start ----------------

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            // ✅ Preview with Camera2 capture callback (live readback)
            val previewBuilder = Preview.Builder()
            Camera2Interop.Extender(previewBuilder)
                .setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: android.hardware.camera2.CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                        val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                        val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)

                        lastIso = iso
                        lastExposureNs = expNs
                        lastFocusDpt = focus

                        runOnUiThread {
                            binding.tvIso.text = iso?.let { "ISO $it" } ?: "ISO —"
                            binding.tvShutter.text =
                                expNs?.let { "Shutter ${"%.3f".format(it / 1_000_000.0)} ms" } ?: "Shutter —"
                            binding.tvFocus.text =
                                focus?.let { "Focus ${"%.2f".format(it)} dpt" } ?: "Focus —"
                        }
                    }
                })

            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.HD,
                        FallbackStrategy.higherQualityOrLowerThan(Quality.HD)
                    )
                )
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    videoCapture
                )

                readCamera2Characteristics()
                applyCamera2Controls()

            } catch (e: Exception) {
                Log.e(TAG, "Binding failed", e)
                Toast.makeText(this, "Camera bind failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun readCamera2Characteristics() {
        val cam = camera ?: return
        val info = Camera2CameraInfo.from(cam.cameraInfo)

        val caps = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        hasManualSensor = caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

        isAwbLockSupported = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
        minFocusDistance = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        exposureRangeNs = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        fpsRanges = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)

        val msg =
            "MANUAL_SENSOR=$hasManualSensor | minFocus=$minFocusDistance | ISO=$isoRange | EXP(ns)=$exposureRangeNs"
        Log.e(TAG, msg)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---------------- Manual controls (tiered) ----------------

    private fun applyCamera2Controls(tier: Int = 1) {
        val cam = camera ?: return
        val c2 = Camera2CameraControl.from(cam.cameraControl)

        fun applyOrNext(opts: CaptureRequestOptions.Builder, currentTier: Int) {
            try {
                c2.setCaptureRequestOptions(opts.build())
                Log.d(TAG, "Tier $currentTier applied")
            } catch (e: Exception) {
                Log.e(TAG, "Tier $currentTier rejected", e)
                Toast.makeText(this, "Tier $currentTier rejected", Toast.LENGTH_SHORT).show()
                if (currentTier < 3) applyCamera2Controls(currentTier + 1)
            }
        }

        // AUTO mode
        if (!binding.swPhotogrammetryLock.isChecked) {
            val auto = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)

            if (isAwbLockSupported) auto.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)

            // Stable FPS only in AUTO
            fpsRanges?.let { ranges ->
                val fixed30 = ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
                val chosen = fixed30 ?: ranges.minByOrNull { (it.upper - it.lower) }
                if (chosen != null) {
                    auto.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chosen)
                }
            }

            applyOrNext(auto, 0)
            return
        }

        // If no MANUAL_SENSOR, skip Tier 1
        val startTier = if (!hasManualSensor && tier == 1) 2 else tier

        when (startTier) {
            // Tier 1: minimum manual exposure (Samsung-safe)
            1 -> {
                val opts = CaptureRequestOptions.Builder()
                opts.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                opts.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                opts.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                opts.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                opts.setCaptureRequestOption(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)

                // DO NOT set AE_TARGET_FPS_RANGE while AE is OFF

                isoRange?.let { r ->
                    val t = binding.sbIso.progress / 1000f
                    val iso = (r.lower + ((r.upper - r.lower) * t)).toInt().coerceIn(r.lower, r.upper)
                    opts.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                }

                exposureRangeNs?.let { r ->
                    val t = binding.sbShutter.progress / 1000f
                    val exp = (r.lower + ((r.upper - r.lower) * t)).toLong().coerceIn(r.lower, r.upper)
                    opts.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                }

                minFocusDistance?.let { mfd ->
                    if (mfd > 0f) {
                        val t = binding.sbFocus.progress / 1000f
                        val focus = (t * mfd).coerceIn(0f, mfd)
                        opts.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
                    }
                }

                // AWB lock can be rejected together with AE_OFF → keep off by default
                // if (isAwbLockSupported) opts.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)

                applyOrNext(opts, 1)
            }

            // Tier 2: focus only, exposure auto
            2 -> {
                val opts = CaptureRequestOptions.Builder()
                opts.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                opts.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                opts.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                opts.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                if (isAwbLockSupported) opts.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)

                minFocusDistance?.let { mfd ->
                    if (mfd > 0f) {
                        val t = binding.sbFocus.progress / 1000f
                        val focus = (t * mfd).coerceIn(0f, mfd)
                        opts.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
                    }
                }

                applyOrNext(opts, 2)
            }

            // Tier 3: full fallback auto
            else -> {
                val fallback = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)

                if (isAwbLockSupported) fallback.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)

                Toast.makeText(this, "Manual rejected → Auto", Toast.LENGTH_SHORT).show()
                applyOrNext(fallback, 3)
            }
        }
    }

    // ---------------- Photo (MediaStore → fallback file) ----------------

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = timestampName("IMG")

        val mediaStorePair = createImageOutputOptions(name)
        if (mediaStorePair == null) {
            Toast.makeText(this, "MediaStore insert failed → app storage", Toast.LENGTH_SHORT).show()
            takePhotoFallbackToFile(capture, name)
            return
        }

        val (imageUri, outputOptions) = mediaStorePair

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    val saved = result.savedUri ?: imageUri
                    saveMetadataJson(baseName = name, mediaUri = saved, type = "photo")
                    Toast.makeText(this@MainActivity, "Photo saved", Toast.LENGTH_SHORT).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo save failed (MediaStore)", exception)
                    Toast.makeText(this@MainActivity, "Saved to app storage (manual mode)", Toast.LENGTH_SHORT).show()
                    takePhotoFallbackToFile(capture, name)
                }
            }
        )
    }

    private fun takePhotoFallbackToFile(capture: ImageCapture, baseName: String) {
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
            .resolve("CameraXApp").apply { mkdirs() }
        val file = dir.resolve("$baseName.jpg")

        val opts = ImageCapture.OutputFileOptions.Builder(file).build()

        capture.takePicture(
            opts,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    val uri = file.toUri()
                    saveMetadataJson(baseName = baseName, mediaUri = uri, type = "photo")
                    Toast.makeText(this@MainActivity, "Saved to app storage", Toast.LENGTH_SHORT).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo save failed (File fallback)", exception)
                    Toast.makeText(this@MainActivity, "Photo failed: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    // ---------------- Video ----------------

    private sealed class VideoOut {
        data class QPlus(val uri: Uri, val opts: MediaStoreOutputOptions) : VideoOut()
        data class Legacy(val uri: Uri, val opts: FileOutputOptions) : VideoOut()
    }

    private fun toggleVideo() {
        val vc = videoCapture ?: return

        if (activeRecording != null) {
            activeRecording?.stop()
            activeRecording = null
            binding.btnVideo.text = "START VIDEO"
            return
        }

        val name = timestampName("VID")
        val out = createVideoOutputOptions(name) ?: run {
            Toast.makeText(this, "Failed to create video output", Toast.LENGTH_SHORT).show()
            return
        }

        val withAudio = binding.swAudio.isChecked
        val audioGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        var pending: PendingRecording = when (out) {
            is VideoOut.QPlus -> vc.output.prepareRecording(this, out.opts)
            is VideoOut.Legacy -> vc.output.prepareRecording(this, out.opts)
        }

        if (withAudio && audioGranted) pending = pending.withAudioEnabled()

        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    binding.btnVideo.text = "STOP VIDEO"
                    Toast.makeText(this, "Recording…", Toast.LENGTH_SHORT).show()
                }

                is VideoRecordEvent.Finalize -> {
                    binding.btnVideo.text = "START VIDEO"
                    activeRecording = null

                    if (!event.hasError()) {
                        val savedUri = event.outputResults.outputUri
                        saveMetadataJson(baseName = name, mediaUri = savedUri, type = "video")
                        Toast.makeText(this, "Video saved", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Video error: ${event.error}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // ---------------- Output options helpers ----------------

    private fun createImageOutputOptions(baseName: String): Pair<Uri?, ImageCapture.OutputFileOptions>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, baseName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraXApp")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            val opts = ImageCapture.OutputFileOptions.Builder(contentResolver, uri, values).build()
            uri to opts
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
                .resolve("CameraXApp").apply { mkdirs() }
            val file = dir.resolve("$baseName.jpg")
            null to ImageCapture.OutputFileOptions.Builder(file).build()
        }
    }

    private fun createVideoOutputOptions(baseName: String): VideoOut? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, baseName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraXApp")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            val opts = MediaStoreOutputOptions.Builder(contentResolver, uri).build()
            VideoOut.QPlus(uri, opts)
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
                .resolve("CameraXApp").apply { mkdirs() }
            val file = dir.resolve("$baseName.mp4")
            val opts = FileOutputOptions.Builder(file).build()
            VideoOut.Legacy(file.toUri(), opts)
        }
    }

    // ---------------- JSON metadata (always app storage) ----------------

    private fun saveMetadataJson(baseName: String, mediaUri: Uri?, type: String) {
        val json = JSONObject().apply {
            put("type", type)
            put("base_name", baseName)
            put("saved_uri", mediaUri?.toString() ?: JSONObject.NULL)

            put("photogrammetry_lock", binding.swPhotogrammetryLock.isChecked)
            put("manual_sensor_supported", hasManualSensor)
            put("awb_lock_supported", isAwbLockSupported)

            // UI request
            put("ui_focus_progress", binding.sbFocus.progress)
            put("ui_iso_progress", binding.sbIso.progress)
            put("ui_shutter_progress", binding.sbShutter.progress)

            // Actual applied values
            put("result_iso", lastIso ?: JSONObject.NULL)
            put("result_exposure_time_ns", lastExposureNs ?: JSONObject.NULL)
            put("result_exposure_time_ms", lastExposureNs?.let { it / 1_000_000.0 } ?: JSONObject.NULL)
            put("result_focus_distance_diopters", lastFocusDpt ?: JSONObject.NULL)

            put("timestamp_ms", System.currentTimeMillis())
            put("android_sdk", Build.VERSION.SDK_INT)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        }

        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
                .resolve("CameraXApp").apply { mkdirs() }
            val file = dir.resolve("$baseName.json")
            file.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "JSON save failed", e)
            Toast.makeText(this, "JSON save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- Misc ----------------

    private fun timestampName(prefix: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        return "${prefix}_$ts"
    }

    private fun allPermissionsGranted(): Boolean =
        REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            if (allPermissionsGranted()) startCamera()
            else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private class SimpleSeek(private val onChange: () -> Unit) : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange()
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) { onChange() }
    }

    companion object {
        private const val TAG = "CAM_DEBUG"
        private const val REQ_PERMS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}
