package com.example.cameraxapp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
import com.example.cameraxapp.databinding.ActivityMainBinding
import com.google.gson.Gson
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

@OptIn(ExperimentalCamera2Interop::class)
class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private var lastCaptureResult: TotalCaptureResult? = null

    private var isoRange: Range<Int>? = null
    private var exposureRangeNs: Range<Long>? = null
    private var minFocusDistance: Float? = null

    private var selectedFps = 30
    private var maxVideoExposureNs = Long.MAX_VALUE

    // WB freeze captured from capture results (after convergence)
    private var frozenGains: RggbChannelVector? = null
    private var frozenTransform: ColorSpaceTransform? = null

    companion object {
        private const val TAG = "CalibrationActivity"

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        private const val DATE_FORMAT = "yyyyMMdd_HHmmss"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFpsSpinner()

        val seekListener = SimpleSeek {
            updateVideoShutterLimit()
            applyCamera2Controls()
        }

        binding.sbFocus.setOnSeekBarChangeListener(seekListener)
        binding.sbIso.setOnSeekBarChangeListener(seekListener)
        binding.sbShutter.setOnSeekBarChangeListener(seekListener)

        binding.swPhotogrammetryLock.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Snap sliders to current camera state
                lastCaptureResult?.let { result ->
                    isoRange?.let { range ->
                        result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { currentIso ->
                            val p = ((currentIso - range.lower).toFloat() / (range.upper - range.lower) * 1000f).toInt()
                            binding.sbIso.progress = p.coerceIn(0, 1000)
                        }
                    }

                    exposureRangeNs?.let { range ->
                        result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { currentExp ->
                            val p = ((currentExp - range.lower).toFloat() / (range.upper - range.lower) * 1000f).toInt()
                            binding.sbShutter.progress = p.coerceIn(0, 1000)
                        }
                    }

                    minFocusDistance?.let { mfd ->
                        if (mfd > 0f) {
                            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { currentFocus ->
                                val p = (currentFocus / mfd * 1000f).toInt()
                                binding.sbFocus.progress = p.coerceIn(0, 1000)
                            }
                        }
                    }
                }
            } else {
                // Clear WB freeze when leaving lock mode (optional)
                frozenGains = null
                frozenTransform = null
            }

            applyCamera2Controls()
        }

        binding.swTorch.setOnCheckedChangeListener { _, on ->
            camera?.cameraControl?.enableTorch(on)
        }

        binding.btnVideo.setOnClickListener { toggleVideo() }
        binding.btnSave.setOnClickListener { saveCalibrationAndGoCapture() }

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (allPermissionsGranted()) startCamera()
        else Toast.makeText(this, "Camera/Audio permission is required", Toast.LENGTH_LONG).show()
    }

    // ---------------- FPS + SHUTTER LIMIT ----------------

    private fun setupFpsSpinner() {
        val fpsValues = listOf(24, 25, 30, 60)
        binding.spVideoFps.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsValues)
        binding.spVideoFps.setSelection(2)

        binding.spVideoFps.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedFps = fpsValues[position]
                updateVideoShutterLimit()
                applyCamera2Controls()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateVideoShutterLimit() {
        val frameDurationNs = 1_000_000_000L / selectedFps
        maxVideoExposureNs = frameDurationNs - 1_000_000L // 1ms margin

        exposureRangeNs?.let { range ->
            val maxAllowed = min(range.upper, maxVideoExposureNs)
            val maxMs = maxAllowed / 1_000_000.0

            binding.tvShutterLimit.text =
                "Max video shutter: %.2f ms @ %d fps".format(maxMs, selectedFps)

            val currentExp = range.lower + ((range.upper - range.lower) *
                    (binding.sbShutter.progress / 1000f)).toLong()

            if (currentExp > maxAllowed) {
                val p = ((maxAllowed - range.lower).toFloat() /
                        (range.upper - range.lower) * 1000f).toInt()
                binding.sbShutter.progress = p.coerceIn(0, 1000)
            }
        }
    }

    // ---------------- CAMERA ----------------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)
                    lastCaptureResult = result

                    // Update focus indicator in AUTO mode
                    if (!binding.swPhotogrammetryLock.isChecked) {
                        val focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: return
                        val mfd = minFocusDistance ?: return
                        if (mfd > 0f) {
                            val cm = if (focusDistance <= 0f) Double.POSITIVE_INFINITY else 100.0 / focusDistance
                            val text = if (cm.isInfinite()) "Focus: ∞" else "Focus: %.1f cm".format(cm)
                            if (binding.tvFocusDistance.text != text) {
                                runOnUiThread { binding.tvFocusDistance.text = text }
                            }
                        }
                    }

                    // If lock is ON and we haven't frozen WB yet, freeze when AWB converges
                    if (binding.swPhotogrammetryLock.isChecked && (frozenGains == null || frozenTransform == null)) {
                        val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)
                        if (awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
                            awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED
                        ) {
                            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                            val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
                            if (gains != null && transform != null) {
                                frozenGains = gains
                                frozenTransform = transform
                                Log.d(TAG, "WB frozen (gains+transform captured)")
                            }
                        }
                    }
                }
            }

            val preview = Preview.Builder().also {
                Camera2Interop.Extender(it).setSessionCaptureCallback(captureCallback)
            }.build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

            imageCapture = ImageCapture.Builder().build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
                videoCapture
            )

            readCameraInfo()
            updateVideoShutterLimit()
            applyCamera2Controls()

        }, ContextCompat.getMainExecutor(this))
    }

    private fun readCameraInfo() {
        val cam = camera ?: return
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        exposureRangeNs = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        minFocusDistance = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
    }

    // ---------------- CAMERA2 CONTROLS ----------------

    private fun applyCamera2Controls() {
        val cam = camera ?: return
        val c2 = Camera2CameraControl.from(cam.cameraControl)
        val opts = CaptureRequestOptions.Builder()

        if (!binding.swPhotogrammetryLock.isChecked) {
            // Auto mode
            opts.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            opts.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            opts.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            opts.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            c2.setCaptureRequestOptions(opts.build())
            binding.tvFocusDistance.text = "Focus: AUTO"
            return
        }

        // ✅ Samsung-safe manual: keep CONTROL_MODE AUTO, but disable AE/AF and set manual params
        opts.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        opts.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        opts.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)

        // ISO
        isoRange?.let { range ->
            val iso = range.lower + ((range.upper - range.lower) * (binding.sbIso.progress / 1000f)).toInt()
            opts.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(range.lower, range.upper))
        }

        // Shutter + fps-safe clamp
        exposureRangeNs?.let { range ->
            val exp = range.lower + ((range.upper - range.lower) * (binding.sbShutter.progress / 1000f)).toLong()
            val clamped = exp.coerceIn(range.lower, min(range.upper, maxVideoExposureNs))
            opts.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, clamped)

            opts.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(selectedFps, selectedFps))
            opts.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / selectedFps)
        }

        // Focus
        minFocusDistance?.let { mfd ->
            if (mfd > 0f) {
                val d = (binding.sbFocus.progress / 1000f) * mfd
                opts.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, d)
                val cm = if (d <= 0f) Double.POSITIVE_INFINITY else 100.0 / d
                binding.tvFocusDistance.text =
                    if (cm.isInfinite()) "Focus: ∞" else "Focus: %.1f cm".format(cm)
            }
        }

        // WB: freeze if we have gains/transform, else keep AWB auto (don’t lock early)
        val gains = frozenGains
        val transform = frozenTransform
        if (gains != null && transform != null) {
            opts.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            opts.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            opts.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
            opts.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
        } else {
            opts.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        }

        c2.setCaptureRequestOptions(opts.build())
    }

    // ---------------- SAVE CALIBRATION ----------------

    private fun saveCalibrationAndGoCapture() {
        val rangeIso = isoRange
        val rangeExp = exposureRangeNs
        val mfd = minFocusDistance

        if (rangeIso == null || rangeExp == null || mfd == null) {
            Toast.makeText(this, "Camera not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        val iso = rangeIso.lower + ((rangeIso.upper - rangeIso.lower) * (binding.sbIso.progress / 1000f)).toInt()
        val exp = rangeExp.lower + ((rangeExp.upper - rangeExp.lower) * (binding.sbShutter.progress / 1000f)).toLong()
        val focus = (binding.sbFocus.progress / 1000f) * mfd

        val clampedExp = exp.coerceIn(rangeExp.lower, min(rangeExp.upper, maxVideoExposureNs))

        val sharedPref = getSharedPreferences("CalibrationData", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putInt("iso", iso)
            putLong("shutterSpeed", clampedExp)
            putFloat("focusDistance", focus)
            putInt("fps", selectedFps)

            val gains = frozenGains
            val transform = frozenTransform
            if (gains != null && transform != null) {
                putString("awbGains", Gson().toJson(gains))
                putString("awbTransform", Gson().toJson(transform))
            }
            apply()
        }

        startActivity(Intent(this, CaptureActivity::class.java))
    }

    // ---------------- VIDEO + METADATA ----------------

    private fun toggleVideo() {
        val vc = videoCapture ?: return

        activeRecording?.let {
            it.stop()
            activeRecording = null
            binding.btnVideo.text = "Start Video"
            return
        }

        val baseName = SimpleDateFormat(DATE_FORMAT, Locale.US).format(System.currentTimeMillis())

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraXApp")
            }
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        activeRecording = vc.output
            .prepareRecording(this, mediaStoreOutput)
            .apply { if (allPermissionsGranted()) withAudioEnabled() }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> binding.btnVideo.text = "Stop Video"
                    is VideoRecordEvent.Finalize -> {
                        binding.btnVideo.text = "Start Video"
                        saveMetadataNextToVideo(baseName)
                    }
                }
            }
    }

    private fun saveMetadataNextToVideo(baseName: String) {
        val sharedPref = getSharedPreferences("CaptureData", Context.MODE_PRIVATE)
        val doctorName = sharedPref.getString("doctorName", "") ?: ""
        val patientId = sharedPref.getString("patientId", "") ?: ""

        val metadata = JSONObject().apply {
            put("doctorName", doctorName)
            put("patientId", patientId)
            put("date", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))

            lastCaptureResult?.let {
                put("iso", it.get(CaptureResult.SENSOR_SENSITIVITY))
                put("shutterNs", it.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                put("focusDistance", it.get(CaptureResult.LENS_FOCUS_DISTANCE))
            }

            frozenGains?.let { put("awbGains", Gson().toJson(it)) }
            frozenTransform?.let { put("awbTransform", Gson().toJson(it)) }
            put("fps", selectedFps)
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/CameraXApp")
            }
        }

        try {
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Log.e(TAG, "Failed to insert metadata file into MediaStore")
                return
            }
            contentResolver.openOutputStream(uri)?.use { os ->
                os.write(metadata.toString(4).toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving metadata", e)
        }
    }

    // ---------------- PERMISSIONS ----------------

    private fun allPermissionsGranted() =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private class SimpleSeek(private val f: () -> Unit) : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, from: Boolean) { if (from) f() }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }
}
