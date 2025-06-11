package com.example.csvmodifier.view

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import com.example.csvmodifier.R
import com.example.csvmodifier.databinding.ActivityMainBinding
import com.example.csvmodifier.model.TimestampIncrementMode
import com.example.csvmodifier.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var currentFileUri: Uri? = null

    private val TAG = "MainActivityLogic"

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) {
                Log.w(TAG, "File selection cancelled or failed.")
                viewModel.setSelectedFile(null)
                return@registerForActivityResult
            }
            currentFileUri = uri
            try {
                var fileNameFromPicker: String? = null
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        fileNameFromPicker = if (nameIndex != -1) cursor.getString(nameIndex) else null
                    }
                }
                val finalFileName = fileNameFromPicker ?: uri.lastPathSegment ?: "Selected_File.csv"
                viewModel.setSelectedFile(finalFileName)

                viewModel.loadCsvHeaders { contentResolver.openInputStream(uri) }

            } catch (e: Exception) {
                Log.e(TAG, "Error setting up selected file: ${e.message}", e)
                Toast.makeText(this, "Error setting up file: ${e.message}", Toast.LENGTH_LONG).show()
                viewModel.setSelectedFile(null)
            }
        }

    private val fileSaverLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { destinationUri: Uri? ->
            if (destinationUri == null) {
                Log.w(TAG, "Save operation cancelled by user.")
                Toast.makeText(this, "Save operation cancelled.", Toast.LENGTH_SHORT).show()
                viewModel.setProcessingStatus("Save cancelled.")
                return@registerForActivityResult
            }

            val sourceUri = currentFileUri
            if (sourceUri == null) {
                viewModel.setErrorMessage("Source file not selected. Please select a file first.")
                return@registerForActivityResult
            }

            val rowsToAddStr = binding.editTextRowsToAdd.text.toString()
            val rowsToAdd = rowsToAddStr.toIntOrNull()
            if (rowsToAdd == null || rowsToAdd <= 0) {
                viewModel.setErrorMessage("Please enter a valid positive number for rows to add.")
                return@registerForActivityResult
            }

            // NEW: Get the increment step value
            val incrementStepStr = binding.editTextIncrementStep.text.toString()
            val incrementStep = incrementStepStr.toLongOrNull()
            if (incrementStep == null || incrementStep <= 0) {
                viewModel.setErrorMessage("Please enter a valid positive number for the increment step.")
                return@registerForActivityResult
            }

            val selectedIncrCols = viewModel.selectedTargetColumns.value ?: emptySet()
            val selectedUuidCols = viewModel.selectedUuidColumns.value ?: emptySet()

            if (selectedIncrCols.isEmpty() && selectedUuidCols.isEmpty()) {
                viewModel.setErrorMessage("Please select at least one column for increment or UUID generation.")
                return@registerForActivityResult
            }

            val generateFromFirstRowOnly = binding.switchFirstRowOnly.isChecked

            val timestampMode = when (binding.radioGroupTimestampMode.checkedRadioButtonId) {
                R.id.radioDayOnly -> TimestampIncrementMode.DAY_ONLY
                R.id.radioTimeOnly -> TimestampIncrementMode.TIME_ONLY
                else -> TimestampIncrementMode.DAY_AND_TIME // Default case
            }

            startStreamingProcess(sourceUri, destinationUri, rowsToAdd, incrementStep, selectedIncrCols.toList(), selectedUuidCols, generateFromFirstRowOnly, timestampMode)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.lifecycleOwner = this
        binding.viewModel = viewModel

        setSupportActionBar(binding.toolbar)

        setupUIListeners()
        setupObservers()
    }

    private fun setupUIListeners() {
        binding.buttonSelectFile.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        binding.buttonSelectTargetColumns.setOnClickListener {
            showColumnSelectionDialog(
                title = "Select Columns for Increment/Date",
                currentSelection = viewModel.selectedTargetColumns.value ?: emptySet(),
                onConfirm = { newSelection -> viewModel.updateSelectedTargetColumns(newSelection) }
            )
        }

        binding.buttonSelectUuidColumns.setOnClickListener {
            showColumnSelectionDialog(
                title = "Select Columns for NEW UUIDs",
                currentSelection = viewModel.selectedUuidColumns.value ?: emptySet(),
                onConfirm = { newSelection -> viewModel.updateSelectedUuidColumns(newSelection) }
            )
        }

        binding.buttonProcessAndSave.setOnClickListener {
            val suggestedName = viewModel.selectedFileName.value?.let { "processed_$it" }
                ?: "processed_output_${System.currentTimeMillis()}.csv"
            fileSaverLauncher.launch(suggestedName)
        }
    }

    private fun startStreamingProcess(
        sourceUri: Uri,
        destUri: Uri,
        rowsToAdd: Int,
        incrementStep: Long, // NEW PARAMETER
        incrCols: List<String>,
        uuidCols: Set<String>,
        generateFromFirstRowOnly: Boolean,
        timestampIncrementMode: TimestampIncrementMode
    ) {
        viewModel.setProcessingStatus("Processing... This may take a while for large files.")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val sourceStream = contentResolver.openInputStream(sourceUri)
                    val destStream = contentResolver.openOutputStream(destUri)
                    if (sourceStream == null || destStream == null) {
                        throw IOException("Failed to open file streams.")
                    }
                    viewModel.processor.processCsvStreaming(sourceStream, destStream, rowsToAdd, incrementStep, incrCols, uuidCols, generateFromFirstRowOnly, timestampIncrementMode)
                } catch (e: Exception) {
                    Log.e(TAG, "Processing failed in background", e)
                    Result.failure(e)
                }
            }

            result.fold(
                onSuccess = { rowsWritten ->
                    viewModel.setProcessingStatus("Success! Wrote $rowsWritten rows to the new file.")
                    Toast.makeText(this@MainActivity, "Processing complete!", Toast.LENGTH_LONG).show()
                },
                onFailure = { error ->
                    viewModel.setErrorMessage("Processing failed: ${error.message}")
                }
            )
        }
    }

    private fun showColumnSelectionDialog(title: String, currentSelection: Set<String>, onConfirm: (Set<String>) -> Unit) {
        val availableHeaders = viewModel.csvHeaders.value
        if (availableHeaders.isNullOrEmpty()) {
            Toast.makeText(this, "Please select a CSV file with headers first.", Toast.LENGTH_SHORT).show()
            return
        }
        val items = availableHeaders.toTypedArray()
        val checkedItems = BooleanArray(items.size) { items[it] in currentSelection }
        val newSelectedTemp = currentSelection.toMutableSet()

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMultiChoiceItems(items, checkedItems) { _, which, isChecked ->
                if (isChecked) newSelectedTemp.add(items[which]) else newSelectedTemp.remove(items[which])
            }
            .setPositiveButton("OK") { dialog, _ -> onConfirm(newSelectedTemp); dialog.dismiss() }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun setupObservers() {
        viewModel.errorMessage.observe(this) { message ->
            message?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                viewModel.clearErrorMessage()
            }
        }
    }
}
