package com.example.csvmodifier.view

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import com.example.csvmodifier.R
import com.example.csvmodifier.databinding.ActivityOptionsBinding
import com.example.csvmodifier.model.TimestampIncrementMode
import com.example.csvmodifier.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class OptionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOptionsBinding
    private val viewModel: MainViewModel by viewModels()
    private var sourceFileUri: Uri? = null

    private var loadingDialog: Dialog? = null
    private var progressTextView: TextView? = null

    private val TAG = "OptionsActivity"

    companion object { const val EXTRA_FILE_URI = "extra_file_uri" }

    private val fileSaverLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { destinationUri: Uri? ->
            if (destinationUri == null) {
                viewModel.setProcessingStatus("Save cancelled.")
                viewModel.setLastSavedFile(null)
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
            var fileName: String? = null
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    fileName = if (nameIndex != -1) cursor.getString(nameIndex) else null
                }
            }
            viewModel.setSelectedFile(fileName ?: uri.lastPathSegment ?: "Selected_File.csv")
            viewModel.loadCsvHeaders { contentResolver.openInputStream(uri) }
        } catch (e: Exception) {
            viewModel.setErrorMessage("Error loading file info: ${e.message}")
        }
    }

    private fun setupUIListeners() {
        binding.buttonSelectValueFromList.setOnClickListener {
            showValueFromListSelectionDialog()
        }
        binding.buttonSelectTargetColumns.setOnClickListener {
            val disabledCols = (viewModel.selectedRandomizeColumns.value ?: emptySet()) +
                    (viewModel.selectedUuidColumns.value ?: emptySet()) +
                    (viewModel.selectedValueFromListColumns.value?.keys ?: emptySet())
            showColumnSelectionDialog("Select Columns for Increment/Date", viewModel.selectedTargetColumns.value ?: emptySet(), disabledCols) { viewModel.updateSelectedTargetColumns(it) }
        }
        binding.buttonSelectRandomizeColumns.setOnClickListener {
            val disabledCols = (viewModel.selectedTargetColumns.value ?: emptySet()) +
                    (viewModel.selectedUuidColumns.value ?: emptySet()) +
                    (viewModel.selectedValueFromListColumns.value?.keys ?: emptySet())
            showColumnSelectionDialog("Select Columns to Randomize", viewModel.selectedRandomizeColumns.value ?: emptySet(), disabledCols) { viewModel.updateSelectedRandomizeColumns(it) }
        }
        binding.buttonSelectUuidColumns.setOnClickListener {
            val disabledCols = (viewModel.selectedTargetColumns.value ?: emptySet()) +
                    (viewModel.selectedRandomizeColumns.value ?: emptySet()) +
                    (viewModel.selectedValueFromListColumns.value?.keys ?: emptySet())
            showColumnSelectionDialog("Select Columns for NEW UUIDs", viewModel.selectedUuidColumns.value ?: emptySet(), disabledCols) { viewModel.updateSelectedUuidColumns(it) }
        }
        binding.buttonProcessAndSave.setOnClickListener {
            val suggestedName = viewModel.selectedFileName.value?.let { "processed_$it" } ?: "processed_output.csv"
            fileSaverLauncher.launch(suggestedName)
        }
        binding.buttonShare.setOnClickListener {
            viewModel.lastSavedFileUri.value?.let { uri -> shareFile(uri) }
                ?: Toast.makeText(this, "No saved file to share.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun gatherOptionsAndProcess(sourceUri: Uri, destUri: Uri) {
        val rowsToAdd = binding.editTextRowsToAdd.text.toString().toIntOrNull() ?: 0
        val dateIncrementStep = binding.editTextDateIncrementStep.text.toString().toLongOrNull() ?: 1L
        val numberIncrementStep = binding.editTextNumberIncrementStep.text.toString().toLongOrNull() ?: 1L

        val incrCols = viewModel.selectedTargetColumns.value ?: emptySet()
        val randCols = viewModel.selectedRandomizeColumns.value ?: emptySet()
        val uuidCols = viewModel.selectedUuidColumns.value ?: emptySet()
        val listCols = viewModel.selectedValueFromListColumns.value ?: emptyMap()

        if (incrCols.isEmpty() && randCols.isEmpty() && uuidCols.isEmpty() && listCols.isEmpty()) {
            viewModel.setErrorMessage("Please select at least one column to modify.")
            return
        }

        val generateFromFirstRowOnly = binding.switchFirstRowOnly.isChecked
        val timestampMode = when (binding.radioGroupTimestampMode.checkedRadioButtonId) {
            R.id.radioDayOnly -> TimestampIncrementMode.DAY_ONLY
            R.id.radioTimeOnly -> TimestampIncrementMode.TIME_ONLY
            else -> TimestampIncrementMode.DAY_AND_TIME
        }

        startStreamingProcess(sourceUri, destUri, rowsToAdd, dateIncrementStep, numberIncrementStep, incrCols.toList(), randCols, uuidCols, listCols, generateFromFirstRowOnly, timestampMode)
    }

    private fun startStreamingProcess(
        sourceUri: Uri, destUri: Uri, rowsToAdd: Int, dateIncrementStep: Long,
        numberIncrementStep: Long, incrCols: List<String>, randCols: Set<String>,
        uuidCols: Set<String>, listCols: Map<String, List<String>>,
        generateFromFirstRowOnly: Boolean, timestampIncrementMode: TimestampIncrementMode
    ) {
        showLoadingDialog()
        viewModel.setProcessingStatus("Preparing...")
        viewModel.setLastSavedFile(null)

        lifecycleScope.launch {
            val totalRowsResult = withContext(Dispatchers.IO) {
                try {
                    viewModel.processor.countRows(contentResolver.openInputStream(sourceUri)!!)
                } catch (e: Exception) { Result.failure<Int>(e) }
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
                    val source = contentResolver.openInputStream(sourceUri)!!
                    val dest = contentResolver.openOutputStream(destUri)!!
                    viewModel.processor.processCsvStreaming(source, dest, rowsToAdd, dateIncrementStep, numberIncrementStep, incrCols, uuidCols, randCols, listCols, generateFromFirstRowOnly, timestampIncrementMode) {
                        viewModel.updateProgress(if (generateFromFirstRowOnly) "$it / $rowsToAdd" else "$it / $totalSourceRows")
                    }
                } catch (e: Exception) { Result.failure<Long>(e) }
            }
            hideLoadingDialog()
            result.fold(
                onSuccess = { rowsWritten ->
                    viewModel.setProcessingStatus("Success! Wrote $rowsWritten rows.")
                    viewModel.setLastSavedFile(destUri)
                },
                onFailure = { error ->
                    viewModel.setErrorMessage("Processing failed: ${error.message}")
                    viewModel.setLastSavedFile(null)
                }
            )
        }
    }

    private fun showValueFromListSelectionDialog() {
        val allHeaders = viewModel.csvHeaders.value ?: return
        val currentListSelections = viewModel.selectedValueFromListColumns.value ?: emptyMap()

        val disabledCols = (viewModel.selectedTargetColumns.value ?: emptySet()) +
                (viewModel.selectedRandomizeColumns.value ?: emptySet()) +
                (viewModel.selectedUuidColumns.value ?: emptySet())

        val availableItems = allHeaders.filter { it !in disabledCols || currentListSelections.containsKey(it) }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Column for List Values")
            .setItems(availableItems) { _, which ->
                val selectedColumn = availableItems[which]
                showEnterListValuesDialog(selectedColumn, currentListSelections[selectedColumn])
            }.show()
    }

    private fun showEnterListValuesDialog(columnName: String, existingValues: List<String>?) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "e.g., value1, value2, value3"
            setText(existingValues?.joinToString(", ") ?: "")
        }

        AlertDialog.Builder(this)
            .setTitle("Enter values for '$columnName'")
            .setMessage("Enter a comma-separated list of values.")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val values = input.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (values.isNotEmpty()) {
                    viewModel.updateValueFromListColumn(columnName, values)
                } else {
                    viewModel.removeValueFromListColumn(columnName) // Remove if list is empty
                }
            }
            .setNegativeButton("Clear") { _, _ ->
                viewModel.removeValueFromListColumn(columnName)
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun showColumnSelectionDialog(title: String, currentSelection: Set<String>, disabledByOtherActions: Set<String>, onConfirm: (Set<String>) -> Unit) {
        val allHeaders = viewModel.csvHeaders.value ?: return
        val availableItems = allHeaders.filter { it !in disabledByOtherActions }.toTypedArray()
        val checkedItems = BooleanArray(availableItems.size) { availableItems[it] in currentSelection }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMultiChoiceItems(availableItems, checkedItems) { _, _, _ -> }
            .setPositiveButton("OK") { dialog, _ ->
                val selected = mutableSetOf<String>()
                val listView = (dialog as AlertDialog).listView
                for (i in 0 until listView.count) {
                    if (listView.isItemChecked(i)) { selected.add(availableItems[i]) }
                }
                onConfirm(selected)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLoadingDialog() { /* ... unchanged ... */ }
    private fun hideLoadingDialog() { /* ... unchanged ... */ }
    private fun shareFile(fileUri: Uri) { /* ... unchanged ... */ }
    private fun setupObservers() { /* ... unchanged ... */ }
}
