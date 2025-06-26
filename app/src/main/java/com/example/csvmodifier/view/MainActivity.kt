package com.example.csvmodifier.view

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.example.csvmodifier.R
import com.example.csvmodifier.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val TAG = "MainActivity"

    // Launcher for the "Modify CSV" flow
    private val modifyFileLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) {
                Log.w(TAG, "Modify file selection cancelled.")
                return@registerForActivityResult
            }
            Log.d(TAG, "File selected for modification. Launching OptionsActivity.")
            val intent = Intent(this, OptionsActivity::class.java).apply {
                putExtra(OptionsActivity.EXTRA_FILE_URI, uri.toString())
            }
            startActivity(intent)
        }

    // Launcher for the "Direct Upload" flow
    private val directUploadFileLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) {
                Log.w(TAG, "Direct upload file selection cancelled.")
                return@registerForActivityResult
            }
            Log.d(TAG, "File selected for direct upload. Launching VeevaUploadActivity.")
            val intent = Intent(this, VeevaUploadActivity::class.java).apply {
                putExtra(VeevaUploadActivity.EXTRA_FILE_URI, uri.toString())
            }
            startActivity(intent)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)

        setSupportActionBar(binding.toolbar)

        // Setup listener for the "Modify" button
        binding.buttonModifyCsv.setOnClickListener {
            Log.d(TAG, "'Modify CSV' button clicked.")
            try {
                modifyFileLauncher.launch("*/*")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch file picker for modify flow", e)
                Toast.makeText(this, "Cannot open file picker.", Toast.LENGTH_LONG).show()
            }
        }

        // Setup listener for the "Direct Upload" button
        binding.buttonDirectUpload.setOnClickListener {
            Log.d(TAG, "'Direct Upload' button clicked.")
            try {
                directUploadFileLauncher.launch("*/*")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch file picker for upload flow", e)
                Toast.makeText(this, "Cannot open file picker.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
