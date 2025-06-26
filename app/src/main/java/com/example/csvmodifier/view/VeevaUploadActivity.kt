package com.example.csvmodifier.view

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.example.csvmodifier.R
import com.example.csvmodifier.databinding.ActivityVeevaUploadBinding
import com.example.csvmodifier.model.VaultObject
import com.example.csvmodifier.model.VeevaActionType
import com.example.csvmodifier.model.VeevaApiUploader
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class VeevaUploadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVeevaUploadBinding
    private var fileToUploadUri: Uri? = null
    private val veevaApiUploader = VeevaApiUploader()
    private var loadingDialog: Dialog? = null
    private var vaultObjects: List<VaultObject> = emptyList()
    private var selectedObject: VaultObject? = null

    private val TAG = "VeevaUploadActivity"

    companion object {
        const val EXTRA_FILE_URI = "extra_file_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_veeva_upload)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val uriString = intent.getStringExtra(EXTRA_FILE_URI)
        if (uriString == null) {
            Toast.makeText(this, "Error: No file URI provided.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        fileToUploadUri = Uri.parse(uriString)

        displayFileInfo(fileToUploadUri!!)
        setupListeners()

        showStep1()
    }

    override fun onSupportNavigateUp(): Boolean {
        // If on step 2, go back to step 1. Otherwise, close the activity.
        if (binding.groupStep2.visibility == View.VISIBLE) {
            showStep1()
            return false
        }
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun displayFileInfo(uri: Uri) {
        var fileName: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    fileName = if (nameIndex != -1) cursor.getString(nameIndex) else null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file name", e)
        }
        binding.textViewSelectedFile.text = "Selected File: ${fileName ?: "Unknown File"}"
    }

    private fun setupListeners() {
        binding.buttonFetchObjects.setOnClickListener {
            val dns = binding.editTextVaultDns.text.toString().trim()
            val user = binding.editTextUsername.text.toString().trim()
            val pass = binding.editTextPassword.text.toString()
            if (dns.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "DNS, Username, and Password are required.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            fetchObjectsFromVault(dns, user, pass)
        }

        binding.buttonUpload.setOnClickListener {
            gatherCredentialsAndUpload()
        }

        binding.radioGroupActionType.setOnCheckedChangeListener { _, checkedId ->
            binding.textFieldKeyField.visibility = if (checkedId == R.id.radioCreate) View.GONE else View.VISIBLE
        }
    }

    private fun fetchObjectsFromVault(dns: String, user: String, pass: String) {
        showLoadingDialog("Authenticating...")
        binding.textViewStatus.text = "Authenticating..."

        veevaApiUploader.authenticate(dns, user, pass) { authResult ->
            runOnUiThread {
                authResult.fold(
                    onSuccess = { sessionId ->
                        (loadingDialog?.findViewById(R.id.textViewLoading) as? TextView)?.text = "Fetching objects..."
                        binding.textViewStatus.text = "Fetching objects..."
                        veevaApiUploader.fetchObjects(dns, sessionId) { fetchResult ->
                            runOnUiThread {
                                hideLoadingDialog()
                                fetchResult.fold(
                                    onSuccess = { objects ->
                                        if (objects.isEmpty()) {
                                            binding.textViewStatus.text = "No loadable objects found for this user."
                                            Toast.makeText(this, "No loadable objects found for this user.", Toast.LENGTH_LONG).show()
                                            return@fold
                                        }
                                        this.vaultObjects = objects
                                        setupObjectSpinner()
                                        showStep2()
                                        binding.textViewStatus.text = ""
                                    },
                                    onFailure = { error ->
                                        binding.textViewStatus.text = "Failed to fetch objects: ${error.message}"
                                        Log.e(TAG, "Failed to fetch objects", error)
                                    }
                                )
                            }
                        }
                    },
                    onFailure = { error ->
                        hideLoadingDialog()
                        binding.textViewStatus.text = "Authentication Failed: ${error.message}"
                        Log.e(TAG, "Authentication failed", error)
                    }
                )
            }
        }
    }

    private fun setupObjectSpinner() {
        val objectLabels = vaultObjects.map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, objectLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerObjectType.adapter = adapter

        binding.spinnerObjectType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedObject = vaultObjects[position]
                Log.d(TAG, "Selected object: ${selectedObject?.label} (${selectedObject?.name})")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedObject = null
            }
        }
    }

    private fun gatherCredentialsAndUpload() {
        if (selectedObject == null) {
            Toast.makeText(this, "Please select an Object Type.", Toast.LENGTH_SHORT).show()
            return
        }
        val dns = binding.editTextVaultDns.text.toString().trim()
        val user = binding.editTextUsername.text.toString().trim()
        val pass = binding.editTextPassword.text.toString()
        val keyField = binding.editTextKeyField.text.toString().trim()

        val actionType = when (binding.radioGroupActionType.checkedRadioButtonId) {
            R.id.radioCreate -> VeevaActionType.CREATE
            R.id.radioUpdate -> VeevaActionType.UPDATE
            R.id.radioDelete -> VeevaActionType.DELETE
            else -> VeevaActionType.UPSERT
        }

        if (actionType != VeevaActionType.CREATE && keyField.isEmpty()) {
            Toast.makeText(this, "Key Field is required for this action.", Toast.LENGTH_SHORT).show()
            return
        }

        uploadToVault(dns, user, pass, selectedObject!!.name, actionType, keyField, fileToUploadUri!!)
    }

    private fun uploadToVault(dns: String, user: String, pass: String, objName: String, action: VeevaActionType, keyField: String?, fileUri: Uri) {
        showLoadingDialog("Uploading...")
        binding.textViewStatus.text = "Authenticating..."

        veevaApiUploader.authenticate(dns, user, pass) { authResult ->
            runOnUiThread {
                authResult.fold(
                    onSuccess = { sessionId ->
                        binding.textViewStatus.text = "Reading & correcting file..."
                        try {
                            val originalCsvData = contentResolver.openInputStream(fileUri)?.bufferedReader().use { it?.readText() }
                            if (originalCsvData.isNullOrEmpty()) {
                                hideLoadingDialog()
                                binding.textViewStatus.text = "Error: Failed to read file or file is empty."
                                return@runOnUiThread
                            }

                            // CORRECTED: Apply the same boolean fix here
                            val correctedCsvData = originalCsvData
                                .replace(Regex("\\bTRUE\\b", RegexOption.IGNORE_CASE), "true")
                                .replace(Regex("\\bFALSE\\b", RegexOption.IGNORE_CASE), "false")

                            binding.textViewStatus.text = "Uploading data..."
                            veevaApiUploader.uploadCsv(dns, sessionId, objName, correctedCsvData, action, keyField) { uploadResult ->
                                runOnUiThread {
                                    hideLoadingDialog()
                                    uploadResult.fold(
                                        onSuccess = { successMessage -> binding.textViewStatus.text = successMessage },
                                        onFailure = { error -> binding.textViewStatus.text = "Upload Error: ${error.message}" }
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            hideLoadingDialog()
                            binding.textViewStatus.text = "File Read Error: ${e.message}"
                        }
                    },
                    onFailure = { error ->
                        hideLoadingDialog()
                        binding.textViewStatus.text = "Authentication Failed: ${error.message}"
                    }
                )
            }
        }
    }

    private fun showLoadingDialog(message: String = "Loading...") {
        if (loadingDialog == null) {
            val builder = AlertDialog.Builder(this)
            val inflater = LayoutInflater.from(this)
            builder.setView(inflater.inflate(R.layout.dialog_loading, null))
            builder.setCancelable(false)
            loadingDialog = builder.create()
        }
        loadingDialog?.show()
        (loadingDialog?.findViewById(R.id.textViewLoading) as? TextView)?.text = message
    }
    private fun hideLoadingDialog() { loadingDialog?.dismiss() }

    private fun showStep1() {
        binding.groupStep1.visibility = View.VISIBLE
        binding.groupStep2.visibility = View.GONE
        supportActionBar?.title = "Step 1: Authenticate"
    }
    private fun showStep2() {
        binding.groupStep1.visibility = View.GONE
        binding.groupStep2.visibility = View.VISIBLE
        supportActionBar?.title = "Step 2: Configure Upload"
    }
}
