package com.example.csvmodifier.viewmodel

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.csvmodifier.model.CsvDataProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

class MainViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    val processor = CsvDataProcessor()

    private val _processingStatus = MutableLiveData<String>()
    val processingStatus: LiveData<String> get() = _processingStatus

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> get() = _errorMessage

    private val _progressText = MutableLiveData<String>()
    val progressText: LiveData<String> get() = _progressText

    private val _lastSavedFileUri = MutableLiveData<Uri?>()
    val lastSavedFileUri: LiveData<Uri?> get() = _lastSavedFileUri

    val selectedFileName: LiveData<String?> = savedStateHandle.getLiveData<String?>("selectedFileNameKey")
    private val _csvHeaders = MutableLiveData<List<String>?>(null)
    val csvHeaders: LiveData<List<String>?> get() = _csvHeaders
    private val _isLoadingHeaders = MutableLiveData<Boolean>(false)
    val isLoadingHeaders: LiveData<Boolean> get() = _isLoadingHeaders
    private val _selectedTargetColumns = MutableLiveData<Set<String>>(emptySet())
    val selectedTargetColumns: LiveData<Set<String>> get() = _selectedTargetColumns
    private val _selectedUuidColumns = MutableLiveData<Set<String>>(emptySet())
    val selectedUuidColumns: LiveData<Set<String>> get() = _selectedUuidColumns
    private val _selectedRandomizeColumns = MutableLiveData<Set<String>>(emptySet())
    val selectedRandomizeColumns: LiveData<Set<String>> get() = _selectedRandomizeColumns

    fun setProcessingStatus(status: String) {
        _processingStatus.postValue(status)
    }

    fun setErrorMessage(message: String?) {
        _errorMessage.postValue(message)
    }

    fun updateProgress(text: String) {
        _progressText.postValue(text)
    }

    fun setLastSavedFile(uri: Uri?) {
        _lastSavedFileUri.postValue(uri)
    }

    fun setSelectedFile(fileName: String?) {
        savedStateHandle["selectedFileNameKey"] = fileName
        if (fileName == null) {
            _csvHeaders.value = null
            _selectedTargetColumns.value = emptySet()
            _selectedUuidColumns.value = emptySet()
            _selectedRandomizeColumns.value = emptySet()
            _lastSavedFileUri.value = null
        }
    }

    fun loadCsvHeaders(inputStreamProvider: () -> InputStream?) {
        val inputStream = inputStreamProvider()
        if (inputStream == null) {
            _errorMessage.value = "Cannot load headers: Error opening file."
            _csvHeaders.value = null
            return
        }
        _isLoadingHeaders.value = true
        _errorMessage.value = null
        _selectedTargetColumns.value = emptySet()
        _selectedUuidColumns.value = emptySet()
        _selectedRandomizeColumns.value = emptySet()
        _lastSavedFileUri.value = null

        viewModelScope.launch {
            try {
                val headerResult = withContext(Dispatchers.IO) {
                    processor.readCsvHeader(inputStream)
                }
                headerResult.fold(
                    onSuccess = { headers -> _csvHeaders.value = headers.sorted() },
                    onFailure = { error ->
                        _csvHeaders.value = null
                        _errorMessage.value = "Error reading CSV headers: ${error.message}"
                    }
                )
            } finally {
                _isLoadingHeaders.value = false
            }
        }
    }

    /**
     * Updates the set of selected columns for incrementing.
     * Removes the selected columns from the other action lists.
     */
    fun updateSelectedTargetColumns(newSelection: Set<String>) {
        _selectedTargetColumns.value = newSelection
        // Remove from other lists
        _selectedRandomizeColumns.value = _selectedRandomizeColumns.value?.minus(newSelection)
        _selectedUuidColumns.value = _selectedUuidColumns.value?.minus(newSelection)
    }

    /**
     * Updates the set of selected columns for UUID generation.
     * Removes the selected columns from the other action lists.
     */
    fun updateSelectedUuidColumns(newSelection: Set<String>) {
        _selectedUuidColumns.value = newSelection
        // Remove from other lists
        _selectedTargetColumns.value = _selectedTargetColumns.value?.minus(newSelection)
        _selectedRandomizeColumns.value = _selectedRandomizeColumns.value?.minus(newSelection)
    }

    /**
     * Updates the set of selected columns for randomization.
     * Removes the selected columns from the other action lists.
     */
    fun updateSelectedRandomizeColumns(newSelection: Set<String>) {
        _selectedRandomizeColumns.value = newSelection
        // Remove from other lists
        _selectedTargetColumns.value = _selectedTargetColumns.value?.minus(newSelection)
        _selectedUuidColumns.value = _selectedUuidColumns.value?.minus(newSelection)
    }

    fun clearErrorMessage() { _errorMessage.value = null }
}
