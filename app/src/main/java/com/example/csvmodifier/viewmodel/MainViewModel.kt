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

class MainViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    val processor = CsvDataProcessor()

    // All existing LiveData...
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
    private val _selectedValueFromListColumns = MutableLiveData<Map<String, List<String>>>(emptyMap())
    val selectedValueFromListColumns: LiveData<Map<String, List<String>>> get() = _selectedValueFromListColumns

    // NEW: LiveData for deletion features
    private val _selectedDeleteColumns = MutableLiveData<Set<String>>(emptySet())
    val selectedDeleteColumns: LiveData<Set<String>> get() = _selectedDeleteColumns

    fun setProcessingStatus(status: String) { _processingStatus.postValue(status) }
    fun setErrorMessage(message: String?) { _errorMessage.postValue(message) }
    fun updateProgress(text: String) { _progressText.postValue(text) }
    fun setLastSavedFile(uri: Uri?) { _lastSavedFileUri.postValue(uri) }

    fun setSelectedFile(fileName: String?) {
        savedStateHandle["selectedFileNameKey"] = fileName
        if (fileName == null) {
            clearAllSelections()
            _csvHeaders.value = null
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
        clearAllSelections()
        _lastSavedFileUri.value = null

        viewModelScope.launch {
            try {
                val headerResult = withContext(Dispatchers.IO) { processor.readCsvHeader(inputStream) }
                headerResult.fold(
                    onSuccess = { headers -> _csvHeaders.value = headers.sorted() },
                    onFailure = { error ->
                        _csvHeaders.value = null
                        _errorMessage.value = "Error reading CSV headers: ${error.message}"
                    }
                )
            } finally { _isLoadingHeaders.value = false }
        }
    }

    private fun clearColumnFromOtherSelections(columnName: String, skipList: LiveData<*>) {
        if (_selectedTargetColumns != skipList) _selectedTargetColumns.value = _selectedTargetColumns.value?.minus(columnName)
        if (_selectedUuidColumns != skipList) _selectedUuidColumns.value = _selectedUuidColumns.value?.minus(columnName)
        if (_selectedRandomizeColumns != skipList) _selectedRandomizeColumns.value = _selectedRandomizeColumns.value?.minus(columnName)
        if (_selectedValueFromListColumns != skipList) _selectedValueFromListColumns.value = _selectedValueFromListColumns.value?.minus(columnName)
        if (_selectedDeleteColumns != skipList) _selectedDeleteColumns.value = _selectedDeleteColumns.value?.minus(columnName)
    }

    fun updateSelectedTargetColumns(newSelection: Set<String>) {
        newSelection.forEach { clearColumnFromOtherSelections(it, _selectedTargetColumns) }
        _selectedTargetColumns.value = newSelection
    }
    fun updateSelectedUuidColumns(newSelection: Set<String>) {
        newSelection.forEach { clearColumnFromOtherSelections(it, _selectedUuidColumns) }
        _selectedUuidColumns.value = newSelection
    }
    fun updateSelectedRandomizeColumns(newSelection: Set<String>) {
        newSelection.forEach { clearColumnFromOtherSelections(it, _selectedRandomizeColumns) }
        _selectedRandomizeColumns.value = newSelection
    }
    fun updateValueFromListColumn(columnName: String, values: List<String>) {
        clearColumnFromOtherSelections(columnName, _selectedValueFromListColumns)
        val newMap = (_selectedValueFromListColumns.value ?: emptyMap()).toMutableMap()
        newMap[columnName] = values
        _selectedValueFromListColumns.value = newMap
    }
    fun removeValueFromListColumn(columnName: String) {
        val newMap = (_selectedValueFromListColumns.value ?: emptyMap()).toMutableMap()
        newMap.remove(columnName)
        _selectedValueFromListColumns.value = newMap
    }
    fun updateSelectedDeleteColumns(newSelection: Set<String>) {
        newSelection.forEach { clearColumnFromOtherSelections(it, _selectedDeleteColumns) }
        _selectedDeleteColumns.value = newSelection
    }

    fun clearAllSelections() {
        _selectedValueFromListColumns.value = emptyMap()
        _selectedTargetColumns.value = emptySet()
        _selectedRandomizeColumns.value = emptySet()
        _selectedUuidColumns.value = emptySet()
        _selectedDeleteColumns.value = emptySet()
    }
    fun clearErrorMessage() { _errorMessage.value = null }
}
