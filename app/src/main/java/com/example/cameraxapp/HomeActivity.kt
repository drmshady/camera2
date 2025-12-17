package com.example.cameraxapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.cameraxapp.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCalibration.setOnClickListener {
            saveCaptureData()
            val intent = Intent(this, CalibrationActivity::class.java)
            startActivity(intent)
        }

        binding.btnCapture.setOnClickListener {
            saveCaptureData()
            val intent = Intent(this, CaptureActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        val sharedPref = getSharedPreferences("CalibrationData", Context.MODE_PRIVATE)
        val isCalibrated = sharedPref.contains("iso")
        binding.btnCapture.isEnabled = isCalibrated
    }

    private fun saveCaptureData() {
        val sharedPref = getSharedPreferences("CaptureData", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString("doctorName", binding.etDoctorName.text.toString())
            putString("patientId", binding.etPatientId.text.toString())
            apply()
        }
    }
}
