package com.example.csvmodifier.viewmodel // Adjust to your package name

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

    val processor = CsvDataProcessor() // Made public for easier access from Activity

    private val _processingStatus = MutableLiveData<String>()
    val processingStatus: LiveData<String> get() = _processingStatus

    // REMOVED: No longer holding processed data in memory
    // private val _processedCsvDataString = MutableLiveData<String?>()

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> get() = _errorMessage

    val selectedFileName: LiveData<String?> = savedStateHandle.getLiveData<String?>("selectedFileNameKey")

    private val _csvHeaders = MutableLiveData<List<String>?>(null)
    val csvHeaders: LiveData<List<String>?> get() = _csvHeaders

    private val _isLoadingHeaders = MutableLiveData<Boolean>(false)
    val isLoadingHeaders: LiveData<Boolean> get() = _isLoadingHeaders

    private val _selectedTargetColumns = MutableLiveData<Set<String>>(emptySet())
    val selectedTargetColumns: LiveData<Set<String>> get() = _selectedTargetColumns

    private val _selectedUuidColumns = MutableLiveData<Set<String>>(emptySet())
    val selectedUuidColumns: LiveData<Set<String>> get() = _selectedUuidColumns

    // Function to update the processing status from the Activity
    fun setProcessingStatus(status: String) {
        _processingStatus.postValue(status)
    }

    fun setErrorMessage(message: String?) {
        _errorMessage.postValue(message)
    }


    fun setSelectedFile(fileName: String?) {
        savedStateHandle["selectedFileNameKey"] = fileName
        if (fileName == null) {
            _csvHeaders.value = null
            _selectedTargetColumns.value = emptySet()
            _selectedUuidColumns.value = emptySet()
        }
    }

    fun loadCsvHeaders(inputStreamProvider: () -> InputStream?) {
        val inputStream = inputStreamProvider()
        if (inputStream == null) {
            _errorMessage.value = "Cannot load headers: No file selected or error opening file."
            _csvHeaders.value = null
            return
        }
        _isLoadingHeaders.value = true
        _errorMessage.value = null
        _selectedTargetColumns.value = emptySet()
        _selectedUuidColumns.value = emptySet()

        viewModelScope.launch {
            try {
                val headerResult = withContext(Dispatchers.IO) {
                    processor.readCsvHeader(inputStream)
                }
                headerResult.fold(
                    onSuccess = { headers ->
                        _csvHeaders.value = headers
                    },
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

    fun updateSelectedTargetColumns(newSelection: Set<String>) {
        _selectedTargetColumns.value = newSelection
    }

    fun updateSelectedUuidColumns(newSelection: Set<String>) {
        _selectedUuidColumns.value = newSelection
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    // REMOVED: Old processCsvFile function. The logic will now live in MainActivity.
}
