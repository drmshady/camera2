package com.example.cameraxapp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.cameraxapp.databinding.ActivityCaptureBinding
import com.google.gson.Gson
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

@ExperimentalCamera2Interop
class CaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptureBinding

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private var iso = 0
    private var shutterSpeed = 0L
    private var focusDistance = 0f
    private var fps = 30
    private var awbGains: RggbChannelVector? = null
    private var awbTransform: ColorSpaceTransform? = null

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        private const val DATE_FORMAT = "yyyyMMdd_HHmmss"
        private const val TAG = "CaptureActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sharedPref = getSharedPreferences("CalibrationData", Context.MODE_PRIVATE)
        iso = sharedPref.getInt("iso", 0)
        shutterSpeed = sharedPref.getLong("shutterSpeed", 0L)
        focusDistance = sharedPref.getFloat("focusDistance", 0f)
        fps = sharedPref.getInt("fps", 30)
        val awbGainsJson = sharedPref.getString("awbGains", null)
        val awbTransformJson = sharedPref.getString("awbTransform", null)

        if (awbGainsJson != null && awbTransformJson != null) {
            awbGains = Gson().fromJson(awbGainsJson, RggbChannelVector::class.java)
            awbTransform = Gson().fromJson(awbTransformJson, ColorSpaceTransform::class.java)
        }

        val focusDistanceInCm = if (focusDistance <= 0f) Double.POSITIVE_INFINITY else 100.0 / focusDistance
        val focusText = if (focusDistanceInCm.isInfinite()) "Focus: ∞" else "Focus: %.1f cm".format(focusDistanceInCm)
        val calibrationData = "ISO: $iso\nShutter Speed: $shutterSpeed\n$focusText"
        binding.tvCaptureData.text = calibrationData

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
        }

        binding.btnRecordVideo.setOnClickListener { toggleVideo() }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture
            )

            val c2 = Camera2CameraControl.from(camera.cameraControl)
            val opts = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, min(shutterSpeed, 1_000_000_000L / fps - 1_000_000L))
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fps)

            val gains = awbGains
            val transform = awbTransform
            if (gains != null && transform != null) {
                opts.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                opts.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                opts.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
                opts.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
            } else {
                opts.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            }

            c2.setCaptureRequestOptions(opts.build())

        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleVideo() {
        val vc = videoCapture ?: return

        activeRecording?.let {
            it.stop()
            activeRecording = null
            binding.btnRecordVideo.text = "Record Video"
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
                    binding.btnRecordVideo.text = "Stop Recording"
                }
                if (it is VideoRecordEvent.Finalize) {
                    binding.btnRecordVideo.text = "Record Video"
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
        metadata.put("iso", iso)
        metadata.put("shutterSpeed", shutterSpeed)
        metadata.put("focusDistance", focusDistance)
        metadata.put("awbGains", awbGains?.let { Gson().toJson(it) })
        metadata.put("awbTransform", awbTransform?.let { Gson().toJson(it) })


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

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
