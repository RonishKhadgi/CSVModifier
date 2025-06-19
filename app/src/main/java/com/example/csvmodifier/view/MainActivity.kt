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

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) {
                Log.w(TAG, "File selection cancelled or failed.")
                Toast.makeText(this, "No file selected.", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            // File was selected, launch the OptionsActivity
            Log.d(TAG, "File selected with URI: $uri. Launching OptionsActivity.")
            val intent = Intent(this, OptionsActivity::class.java).apply {
                putExtra(OptionsActivity.EXTRA_FILE_URI, uri.toString())
             }
            startActivity(intent)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)

        setSupportActionBar(binding.toolbar)

        binding.buttonSelectFile.setOnClickListener {
            Log.d(TAG, "Select File button clicked. Launching file picker.")
            try {
                filePickerLauncher.launch("*/*") // General MIME type for better compatibility
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch file picker", e)
                Toast.makeText(this, "Cannot open file picker. No suitable app found.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
