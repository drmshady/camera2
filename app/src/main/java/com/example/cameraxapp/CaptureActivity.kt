package com.example.cameraxapp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@ExperimentalCamera2Interop
class CaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptureBinding

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private var iso = 0
    private var shutterSpeed = 0L
    private var focusDistance = 0f

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        private const val DATE_FORMAT = "yyyyMMdd_HHmmss"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sharedPref = getSharedPreferences("CalibrationData", Context.MODE_PRIVATE)
        iso = sharedPref.getInt("iso", 0)
        shutterSpeed = sharedPref.getLong("shutterSpeed", 0L)
        focusDistance = sharedPref.getFloat("focusDistance", 0f)

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
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterSpeed)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                .build()
            c2.setCaptureRequestOptions(opts)

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

        activeRecording = vc.output.prepareRecording(this, mediaStoreOutput)
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

        try {
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).toString() + "/CameraXApp"
            val dir = File(path)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(path, "$videoName.json")
            FileOutputStream(file).use {
                it.write(metadata.toString(4).toByteArray())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
