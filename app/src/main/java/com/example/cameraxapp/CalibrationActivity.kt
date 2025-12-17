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
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Rational
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
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.cameraxapp.databinding.ActivityMainBinding
import com.google.gson.Gson
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

@ExperimentalCamera2Interop
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
    private var isAwbLockSupported = false

    private var selectedFps = 30
    private var maxVideoExposureNs = Long.MAX_VALUE

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        private const val DATE_FORMAT = "yyyyMMdd_HHmmss"
        private const val TAG = "CalibrationActivity"
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
                lastCaptureResult?.let { result ->
                    // Set ISO
                    result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { currentIso ->
                        isoRange?.let { range ->
                            val progress = ((currentIso - range.lower).toFloat() / (range.upper - range.lower) * 1000f).toInt()
                            binding.sbIso.progress = progress
                        }
                    }

                    // Set shutter
                    result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { currentExposure ->
                        exposureRangeNs?.let { range ->
                            val progress = ((currentExposure - range.lower).toFloat() / (range.upper - range.lower) * 1000f).toInt()
                            binding.sbShutter.progress = progress
                        }
                    }

                    // Set focus
                    result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { currentFocus ->
                        minFocusDistance?.let { mfd ->
                            if (mfd > 0f) {
                                val progress = (currentFocus / mfd * 1000f).toInt()
                                binding.sbFocus.progress = progress
                            }
                        }
                    }
                }
            }
            applyCamera2Controls()
        }

        binding.swTorch.setOnCheckedChangeListener { _, on ->
            camera?.cameraControl?.enableTorch(on)
        }

        binding.btnVideo.setOnClickListener { toggleVideo() }

        binding.btnSave.setOnClickListener { saveCalibration() }

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
    }

    private fun saveCalibration() {
        val sharedPref = getSharedPreferences("CalibrationData", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putInt("iso", isoRange?.let { it.lower + ((it.upper - it.lower) * (binding.sbIso.progress / 1000f)).toInt() } ?: 0)
            putLong("shutterSpeed", exposureRangeNs?.let { it.lower + ((it.upper - it.lower) * (binding.sbShutter.progress / 1000f)).toLong() } ?: 0L)
            putFloat("focusDistance", minFocusDistance?.let { (binding.sbFocus.progress / 1000f) * it } ?: 0f)
            putInt("fps", selectedFps)

            lastCaptureResult?.let {
                val gains = it.get(CaptureResult.COLOR_CORRECTION_GAINS)
                val transform = it.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
                if (gains != null && transform != null) {
                    putString("awbGains", Gson().toJson(gains))
                    putString("awbTransform", Gson().toJson(transform))
                } else {
                    Toast.makeText(this@CalibrationActivity, "AWB data not available", Toast.LENGTH_SHORT).show()
                    return
                }
            }

            apply()
        }

        val intent = Intent(this, CaptureActivity::class.java)
        startActivity(intent)
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

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
    }

    private fun updateVideoShutterLimit() {
        val frameDurationNs = 1_000_000_000L / selectedFps
        maxVideoExposureNs = frameDurationNs - 1_000_000L

        exposureRangeNs?.let { range ->
            val maxAllowed = min(range.upper, maxVideoExposureNs)
            val maxMs = maxAllowed / 1_000_000.0

            binding.tvShutterLimit.text =
                "Max video shutter: %.2f ms @ %d fps".format(maxMs, selectedFps)

            val currentExp =
                range.lower + ((range.upper - range.lower) *
                        (binding.sbShutter.progress / 1000f)).toLong()

            if (currentExp > maxAllowed) {
                val p =
                    ((maxAllowed - range.lower).toFloat() /
                            (range.upper - range.lower) * 1000f).toInt()
                binding.sbShutter.progress = p
            }
        }
    }

    // ---------------- CAMERA ----------------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    lastCaptureResult = result

                    // Update focus distance indicator when not in manual mode
                    if (!binding.swPhotogrammetryLock.isChecked) {
                        result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { focusDistance ->
                            minFocusDistance?.let { mfd ->
                                if (mfd > 0) {
                                    val cm = if (focusDistance <= 0f) Double.POSITIVE_INFINITY else 100.0 / focusDistance
                                    val text = if (cm.isInfinite()) "Focus: ∞" else "Focus: %.1f cm".format(cm)
                                    if (binding.tvFocusDistance.text != text) {
                                        runOnUiThread { binding.tvFocusDistance.text = text }
                                    }
                                }
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
        val info = Camera2CameraInfo.from(camera!!.cameraInfo)
        isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        exposureRangeNs =
            info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        minFocusDistance =
            info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        isAwbLockSupported =
            info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
    }

    // ---------------- CAMERA2 CONTROLS ----------------

    private fun applyCamera2Controls() {
        val cam = camera ?: return
        val c2 = Camera2CameraControl.from(cam.cameraControl)
        val opts = CaptureRequestOptions.Builder()

        if (!binding.swPhotogrammetryLock.isChecked) {
            opts.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            opts.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            opts.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_ON
            )
            opts.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CameraMetadata.CONTROL_AWB_MODE_AUTO
            )
            if (isAwbLockSupported) {
                opts.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
            }
            c2.setCaptureRequestOptions(opts.build())
            binding.tvFocusDistance.text = "Focus: AUTO"
            return
        }

        opts.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
        opts.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        opts.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        opts.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        if (isAwbLockSupported) {
            opts.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        }

        isoRange?.let {
            val iso = it.lower + ((it.upper - it.lower) *
                    (binding.sbIso.progress / 1000f)).toInt()
            opts.setCaptureRequestOption(
                CaptureRequest.SENSOR_SENSITIVITY,
                iso.coerceIn(it.lower, it.upper)
            )
        }

        exposureRangeNs?.let {
            val exp = it.lower + ((it.upper - it.lower) *
                    (binding.sbShutter.progress / 1000f)).toLong()
            opts.setCaptureRequestOption(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                exp.coerceIn(it.lower, min(it.upper, maxVideoExposureNs))
            )
            opts.setCaptureRequestOption(
                CaptureRequest.SENSOR_FRAME_DURATION,
                1_000_000_000L / selectedFps
            )
        }

        minFocusDistance?.let { mfd ->
            if (mfd > 0f) {
                val d = (binding.sbFocus.progress / 1000f) * mfd
                opts.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, d)
                val cm = if (d <= 0f) Double.POSITIVE_INFINITY else 100.0 / d
                binding.tvFocusDistance.text =
                    if (cm.isInfinite()) "Focus: ∞" else "Focus: %.1f cm".format(cm)
            }
        }

        c2.setCaptureRequestOptions(opts.build())
    }

    // ---------------- CAPTURE ----------------



    private fun toggleVideo() {
        val vc = videoCapture ?: return

        activeRecording?.let {
            it.stop()
            activeRecording = null
            binding.btnVideo.text = "Start Video"
            return
        }

        val name = SimpleDateFormat(DATE_FORMAT, Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraXApp")
            }
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        activeRecording = vc.output
            .prepareRecording(this, mediaStoreOutput)
            .apply { if (allPermissionsGranted()) withAudioEnabled() }
            .start(ContextCompat.getMainExecutor(this)) {
                if (it is VideoRecordEvent.Start) {
                    binding.btnVideo.text = "Stop Video"
                }
                if (it is VideoRecordEvent.Finalize) {
                    binding.btnVideo.text = "Start Video"
                    saveMetadata(name)
                    // TODO: Upload video and metadata to cloud
                }
            }
    }

    private fun saveMetadata(videoName: String) {
        val sharedPref = getSharedPreferences("CaptureData", Context.MODE_PRIVATE)
        val doctorName = sharedPref.getString("doctorName", "")
        val patientId = sharedPref.getString("patientId", "")

        val metadata = JSONObject()
        metadata.put("doctorName", doctorName)
        metadata.put("patientId", patientId)
        metadata.put("date", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
        lastCaptureResult?.let {
            metadata.put("iso", it.get(CaptureResult.SENSOR_SENSITIVITY))
            metadata.put("shutterSpeed", it.get(CaptureResult.SENSOR_EXPOSURE_TIME))
            metadata.put("focusDistance", it.get(CaptureResult.LENS_FOCUS_DISTANCE))
            val gains = it.get(CaptureResult.COLOR_CORRECTION_GAINS)
            val transform = it.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            if (gains != null && transform != null) {
                metadata.put("awbGains", Gson().toJson(gains))
                metadata.put("awbTransform", Gson().toJson(transform))
            }
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$videoName.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CameraXApp")
            }
        }

        try {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val cameraXAppDir = File(downloadsDir, "CameraXApp")
                if (!cameraXAppDir.exists()) {
                    cameraXAppDir.mkdirs()
                }
                val file = File(cameraXAppDir, "$videoName.json")
                FileOutputStream(file).use { outputStream ->
                    outputStream.write(metadata.toString(4).toByteArray())
                }
                return
            }
            contentResolver.insert(collection, contentValues)?.also { uri ->
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(metadata.toString(4).toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving metadata", e)
        }
    }


    private fun allPermissionsGranted() =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) ==
                    PackageManager.PERMISSION_GRANTED
        }

    private class SimpleSeek(val f: () -> Unit) : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, from: Boolean) {
            if (from) f()
        }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }
}
