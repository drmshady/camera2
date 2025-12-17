package com.example.cameraxapp

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.cameraxapp.databinding.ActivityMainBinding
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var imageAnalysis: ImageAnalysis? = null

    // Camera Specs for Photogrammetry
    private var isoRange: Range<Int>? = null
    private var exposureRangeNs: Range<Long>? = null
    private var minFocusDistance: Float? = null
    private var hasManualSensor: Boolean = false
    private var isAwbLockSupported: Boolean = false
    private var fpsRanges: Array<Range<Int>>? = null

    private var qualityOkStreak = 0
    private var prevMotionSample: IntArray? = null
    private var sharpEma = 0.0
    private var sharpPeak = 0.0


    companion object {
        private const val TAG = "CAM_DEBUG"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        private const val STREAK_REQUIRED = 12
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
        }

        binding.btnPhoto.setOnClickListener { takePhoto() }
        binding.btnVideo.setOnClickListener { toggleVideo() }

        val seekListener = SimpleSeek { applyCamera2Controls() }
        binding.sbFocus.setOnSeekBarChangeListener(seekListener)
        binding.sbIso.setOnSeekBarChangeListener(seekListener)
        binding.sbShutter.setOnSeekBarChangeListener(seekListener)
        binding.swPhotogrammetryLock.setOnCheckedChangeListener { _, _ -> applyCamera2Controls() }
        binding.swTorch.setOnCheckedChangeListener { _, on ->
            camera?.cameraControl?.enableTorch(on)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, videoCapture, imageAnalysis
                )

                startImageAnalysis()
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
    }

    private fun analyzeQualityRoi(image: ImageProxy) {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height

        val roiW = (w * 0.25).toInt()
        val roiH = (h * 0.25).toInt()
        val roiL = (w - roiW) / 2
        val roiT = (h - roiH) / 2
        val roiR = roiL + roiW
        val roiB = roiT + roiH
        val step = 4

        var sum = 0L
        var n = 0
        var clipWhite = 0
        var clipBlack = 0
        var specular = 0

        val motionStep = 16
        val motionW = ((roiW) / motionStep).coerceAtLeast(1)
        val motionH = ((roiH) / motionStep).coerceAtLeast(1)
        val motionNow = IntArray(motionW * motionH)
        var mi = 0

        for (y in roiT until roiB step step) {
            val rowBase = y * rowStride
            for (x in roiL until roiR step step) {
                val v = (buf.get(rowBase + x).toInt() and 0xFF)
                sum += v
                n++
                if (v >= 250) clipWhite++
                if (v <= 5) clipBlack++
                if (v >= 245) specular++
            }
        }

        for (yy in 0 until motionH) {
            val y = roiT + yy * motionStep
            val rowBase = y * rowStride
            for (xx in 0 until motionW) {
                val x = roiL + xx * motionStep
                val v = (buf.get(rowBase + x).toInt() and 0xFF)
                motionNow[mi++] = v
            }
        }

        val mean = if (n > 0) sum.toDouble() / n else 0.0
        val whitePct = if (n > 0) clipWhite * 100.0 / n else 0.0
        val blackPct = if (n > 0) clipBlack * 100.0 / n else 0.0
        val specPct = if (n > 0) specular * 100.0 / n else 0.0

        var lapSum = 0.0
        var lapSumSq = 0.0
        var lapN = 0
        val lapStep = 10
        val yMin = (roiT + lapStep).coerceAtMost(h - lapStep - 1)
        val yMax = (roiB - lapStep).coerceAtLeast(yMin + 1)
        val xMin = (roiL + lapStep).coerceAtMost(w - lapStep - 1)
        val xMax = (roiR - lapStep).coerceAtLeast(xMin + 1)

        for (y in yMin until yMax step lapStep) {
            val rowC = y * rowStride
            val rowU = (y - lapStep) * rowStride
            val rowD = (y + lapStep) * rowStride
            for (x in xMin until xMax step lapStep) {
                val c = (buf.get(rowC + x).toInt() and 0xFF)
                val up = (buf.get(rowU + x).toInt() and 0xFF)
                val down = (buf.get(rowD + x).toInt() and 0xFF)
                val left = (buf.get(rowC + x - lapStep).toInt() and 0xFF)
                val right = (buf.get(rowC + x + lapStep).toInt() and 0xFF)
                val lap = (c * 4) - up - down - left - right
                lapSum += lap
                lapSumSq += lap * lap
                lapN++
            }
        }

        val lapVar = if (lapN > 0) (lapSumSq / lapN) - ((lapSum / lapN) * (lapSum / lapN)) else 0.0

        var mad = 0.0
        val prev = prevMotionSample
        if (prev != null && prev.size == motionNow.size) {
            var diffSum = 0.0
            for (i in prev.indices) {
                diffSum += kotlin.math.abs(prev[i] - motionNow[i])
            }
            mad = if (prev.isNotEmpty()) diffSum / prev.size else 0.0
        }

        prevMotionSample = motionNow

        val expOk = mean in 90.0..180.0 && blackPct < 1.0 && whitePct < 1.0 && specPct < 0.2
        // ---- Noise estimate (so high ISO noise won't fake "sharpness") ----
        var noiseSum = 0.0
        var noiseN = 0
        val noiseStep = 8
        for (y in roiT until roiB - noiseStep step noiseStep) {
            val row = y * rowStride
            val rowD = (y + noiseStep) * rowStride
            for (x in roiL until roiR - noiseStep step noiseStep) {
                val a = (buf.get(row + x).toInt() and 0xFF)
                val b = (buf.get(row + x + noiseStep).toInt() and 0xFF)
                val c = (buf.get(rowD + x).toInt() and 0xFF)
                noiseSum += kotlin.math.abs(a - b) + kotlin.math.abs(a - c)
                noiseN += 2
            }
        }
        val noiseMad = if (noiseN > 0) noiseSum / noiseN else 0.0

// Noise-normalized sharpness score (higher = truly sharper)
        val sharpScore = lapVar / ((noiseMad + 1.0) * (noiseMad + 1.0))

// Thresholds: tune these if needed
        val focusOk = sharpScore > 8.0

        val motionOk = mad < 10.0
        val qualityOk = expOk && focusOk && motionOk

        if (qualityOk) {
            qualityOkStreak++
        } else {
            qualityOkStreak = 0
        }

        val stableGood = qualityOkStreak >= STREAK_REQUIRED
        val statusText = when {
            !expOk -> "Quality: Bad Exposure"
            !focusOk -> "Quality: Bad Focus (Var: ${String.format("%.1f", lapVar)})"
            !motionOk -> "Quality: HOLD STILL... (MAD: ${String.format("%.1f", mad)})"
            !stableGood -> "Quality: Stabilizing ($qualityOkStreak/$STREAK_REQUIRED)"
            else -> "Quality: GOOD"
        }

        runOnUiThread {
            binding.tvQuality.text = statusText
            binding.vRoi.setBackgroundResource(if (stableGood) R.drawable.roi_good else R.drawable.roi_bad)
            binding.btnPhoto.isEnabled = true
        }
    }

    private fun startImageAnalysis() {
        imageAnalysis?.setAnalyzer(ContextCompat.getMainExecutor(this)) { image ->
            try {
                analyzeQualityRoi(image)
            } catch (t: Throwable) {
                Log.e(TAG, "Quality analysis error", t)
            } finally {
                image.close()
            }
        }
    }

    private fun stopImageAnalysis() {
        imageAnalysis?.clearAnalyzer()
    }

    private fun applyCamera2Controls() {
        val c2Control = camera?.let { Camera2CameraControl.from(it.cameraControl) } ?: return

        if (!binding.swPhotogrammetryLock.isChecked) {
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            if (isAwbLockSupported) {
                options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
            }
            c2Control.setCaptureRequestOptions(options.build())
            return
        }

        val options = CaptureRequestOptions.Builder()
        options.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        if (isAwbLockSupported) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        }

        isoRange?.let {
            val iso = it.lower + ((it.upper - it.lower) * (binding.sbIso.progress / 1000f)).toInt()
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(it.lower, it.upper))
        }
        exposureRangeNs?.let {
            val exp = it.lower + ((it.upper - it.lower) * (binding.sbShutter.progress / 1000f)).toLong()
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp.coerceIn(it.lower, it.upper))
        }
        minFocusDistance?.let { minFocus ->
            if (minFocus > 0) {
                val focus = (binding.sbFocus.progress / 1000f) * minFocus
                options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus.coerceIn(0f, minFocus))
            }
        }

        c2Control.setCaptureRequestOptions(options.build())
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraXApp")
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues).build()

        ic.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                saveJsonNextToMedia(name, "photo", output.savedUri)
            }

            override fun onError(exc: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
            }
        })
    }

    private fun toggleVideo() {
        val vc = videoCapture ?: return

        activeRecording?.let {
            it.stop()
            activeRecording = null
            startImageAnalysis()
            binding.btnVideo.text = "Start Video"
            return
        }

        stopImageAnalysis()

        val cam = camera ?: return
        val c2Control = Camera2CameraControl.from(cam.cameraControl)
        val videoOpts = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            .build()
        c2Control.setCaptureRequestOptions(videoOpts)

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraXApp")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        activeRecording = vc.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply { if (allPermissionsGranted()) withAudioEnabled() }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        binding.btnVideo.text = "Stop Video"
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            saveJsonNextToMedia(name, "video", recordEvent.outputResults.outputUri)
                        } else {
                            Log.e(TAG, "Video capture error: ${recordEvent.error}")
                        }
                        binding.btnVideo.text = "Start Video"
                    }
                }
            }
    }

    private fun saveJsonNextToMedia(baseName: String, type: String, mediaUri: Uri?) {
        val json = JSONObject()
        // ... (omitted for brevity)

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (type == "photo") "Pictures/CameraXApp" else "Movies/CameraXApp")
            }
        }

        try {
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    os.write(json.toString(4).toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata", e)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private class SimpleSeek(val onProgress: () -> Unit) : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { if (f) onProgress() }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }
}
