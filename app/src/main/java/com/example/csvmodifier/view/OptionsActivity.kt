package com.example.csvmodifier.view

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import com.example.csvmodifier.R
import com.example.csvmodifier.databinding.ActivityOptionsBinding
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

class OptionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOptionsBinding
    private val viewModel: MainViewModel by viewModels()
    private var sourceFileUri: Uri? = null

    private var loadingDialog: Dialog? = null
    private var progressTextView: TextView? = null

    private val TAG = "OptionsActivity"

    companion object {
        const val EXTRA_FILE_URI = "extra_file_uri"
    }

    private val fileSaverLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { destinationUri: Uri? ->
            if (destinationUri == null) {
                viewModel.setProcessingStatus("Save cancelled.")
                return@registerForActivityResult
            }
            if (sourceFileUri == null) {
                viewModel.setErrorMessage("Source file URI is missing.")
                return@registerForActivityResult
            }
            gatherOptionsAndProcess(sourceFileUri!!, destinationUri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_options)
        binding.lifecycleOwner = this
        binding.viewModel = viewModel

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val uriString = intent.getStringExtra(EXTRA_FILE_URI)
        if (uriString == null) {
            Toast.makeText(this, "Error: No file URI provided.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        sourceFileUri = Uri.parse(uriString)

        loadInitialFileInfo(sourceFileUri!!)
        setupUIListeners()
        setupObservers()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun loadInitialFileInfo(uri: Uri) {
        try {
            var fileNameFromPicker: String? = null
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    fileNameFromPicker = if (nameIndex != -1) cursor.getString(nameIndex) else null
                }
            }
            viewModel.setSelectedFile(fileNameFromPicker ?: uri.lastPathSegment ?: "Selected_File.csv")
            viewModel.loadCsvHeaders { contentResolver.openInputStream(uri) }
        } catch (e: Exception) {
            viewModel.setErrorMessage("Error loading file info: ${e.message}")
        }
    }

    private fun setupUIListeners() {
        binding.buttonSelectTargetColumns.setOnClickListener {
            showColumnSelectionDialog("Select Columns for Increment/Date", viewModel.selectedTargetColumns.value ?: emptySet()) { viewModel.updateSelectedTargetColumns(it) }
        }
        binding.buttonSelectRandomizeColumns.setOnClickListener {
            showColumnSelectionDialog("Select Columns to Randomize", viewModel.selectedRandomizeColumns.value ?: emptySet()) { viewModel.updateSelectedRandomizeColumns(it) }
        }
        binding.buttonSelectUuidColumns.setOnClickListener {
            showColumnSelectionDialog("Select Columns for NEW UUIDs", viewModel.selectedUuidColumns.value ?: emptySet()) { viewModel.updateSelectedUuidColumns(it) }
        }
        binding.buttonProcessAndSave.setOnClickListener {
            val suggestedName = viewModel.selectedFileName.value?.let { "processed_$it" } ?: "processed_output.csv"
            fileSaverLauncher.launch(suggestedName)
        }
    }

    private fun gatherOptionsAndProcess(sourceUri: Uri, destUri: Uri) {
        val rowsToAdd = binding.editTextRowsToAdd.text.toString().toIntOrNull()
        if (rowsToAdd == null || rowsToAdd <= 0) {
            viewModel.setErrorMessage("Please enter a valid positive number for rows to add.")
            return
        }
        val dateIncrementStep = binding.editTextDateIncrementStep.text.toString().toLongOrNull()
        if (dateIncrementStep == null || dateIncrementStep <= 0) {
            viewModel.setErrorMessage("Please enter a valid positive number for the Date/Time increment step.")
            return
        }
        val numberIncrementStep = binding.editTextNumberIncrementStep.text.toString().toLongOrNull()
        if (numberIncrementStep == null || numberIncrementStep <= 0) {
            viewModel.setErrorMessage("Please enter a valid positive number for the Number increment step.")
            return
        }

        val incrCols = viewModel.selectedTargetColumns.value ?: emptySet()
        val randCols = viewModel.selectedRandomizeColumns.value ?: emptySet()
        val uuidCols = viewModel.selectedUuidColumns.value ?: emptySet()

        if (incrCols.isEmpty() && randCols.isEmpty() && uuidCols.isEmpty()) {
            viewModel.setErrorMessage("Please select at least one column to modify.")
            return
        }

        val generateFromFirstRowOnly = binding.switchFirstRowOnly.isChecked
        val timestampMode = when (binding.radioGroupTimestampMode.checkedRadioButtonId) {
            R.id.radioDayOnly -> TimestampIncrementMode.DAY_ONLY
            R.id.radioTimeOnly -> TimestampIncrementMode.TIME_ONLY
            else -> TimestampIncrementMode.DAY_AND_TIME
        }

        startStreamingProcess(sourceUri, destUri, rowsToAdd, dateIncrementStep, numberIncrementStep, incrCols.toList(), randCols, uuidCols, generateFromFirstRowOnly, timestampMode)
    }

    private fun startStreamingProcess(
        sourceUri: Uri, destUri: Uri, rowsToAdd: Int, dateIncrementStep: Long,
        numberIncrementStep: Long, incrCols: List<String>, randCols: Set<String>,
        uuidCols: Set<String>, generateFromFirstRowOnly: Boolean,
        timestampIncrementMode: TimestampIncrementMode
    ) {
        showLoadingDialog()
        viewModel.setProcessingStatus("Preparing...")

        lifecycleScope.launch {
            val totalRowsResult = withContext(Dispatchers.IO) {
                try {
                    val countStream = contentResolver.openInputStream(sourceUri) ?: throw IOException("Failed to open file for counting.")
                    viewModel.processor.countRows(countStream)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            if (totalRowsResult.isFailure) {
                hideLoadingDialog()
                viewModel.setErrorMessage("Failed to count rows: ${totalRowsResult.exceptionOrNull()?.message}")
                return@launch
            }

            val totalSourceRows = totalRowsResult.getOrDefault(0)
            viewModel.setProcessingStatus("Processing...")

            val result = withContext(Dispatchers.IO) {
                try {
                    val sourceStream = contentResolver.openInputStream(sourceUri) ?: throw IOException("Failed to open file for processing.")
                    val destStream = contentResolver.openOutputStream(destUri) ?: throw IOException("Failed to open destination file.")

                    viewModel.processor.processCsvStreaming(
                        sourceStream, destStream, rowsToAdd, dateIncrementStep, numberIncrementStep,
                        incrCols, uuidCols, randCols, generateFromFirstRowOnly, timestampIncrementMode
                    ) { processedCount ->
                        val progressString = if (generateFromFirstRowOnly) {
                            "$processedCount / $rowsToAdd rows generated"
                        } else {
                            "$processedCount / $totalSourceRows rows processed"
                        }
                        viewModel.updateProgress(progressString)
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            hideLoadingDialog()
            result.fold(
                onSuccess = { rowsWritten ->
                    viewModel.setProcessingStatus("Success! Wrote $rowsWritten rows to the new file.")
                    Toast.makeText(this@OptionsActivity, "Processing complete!", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "Headers not available.", Toast.LENGTH_SHORT).show()
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

    private fun showLoadingDialog() {
        if (loadingDialog == null) {
            val builder = AlertDialog.Builder(this)
            val inflater = LayoutInflater.from(this)
            val view = inflater.inflate(R.layout.dialog_loading, null)
            progressTextView = view.findViewById(R.id.textViewProgress)
            builder.setView(view)
            builder.setCancelable(false)
            loadingDialog = builder.create()
        }
        progressTextView?.text = ""
        loadingDialog?.show()
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
    }

    private fun setupObservers() {
        viewModel.errorMessage.observe(this) { message ->
            message?.let {
                hideLoadingDialog()
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                viewModel.clearErrorMessage()
            }
        }
        viewModel.progressText.observe(this) { progress ->
            progressTextView?.text = progress
        }
    }
}
