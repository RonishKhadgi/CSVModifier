package com.example.csvmodifier.view

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.example.csvmodifier.R
import com.example.csvmodifier.databinding.ActivityVeevaUploadBinding
import com.example.csvmodifier.model.VeevaActionType
import com.example.csvmodifier.model.VeevaApiUploader
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class VeevaUploadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVeevaUploadBinding
    private var fileToUploadUri: Uri? = null
    private val veevaApiUploader = VeevaApiUploader()
    private var loadingDialog: Dialog? = null

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
            finish()
            return
        }
        fileToUploadUri = Uri.parse(uriString)

        displayFileInfo(fileToUploadUri!!)

        // Listener to show/hide Key Field when action type changes
        binding.radioGroupActionType.setOnCheckedChangeListener { _, checkedId ->
            binding.textFieldKeyField.visibility = if (checkedId == R.id.radioCreate) View.GONE else View.VISIBLE
        }

        binding.buttonUpload.setOnClickListener {
            gatherCredentialsAndUpload()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
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

    private fun gatherCredentialsAndUpload() {
        val dns = binding.editTextVaultDns.text.toString().trim()
        val user = binding.editTextUsername.text.toString().trim()
        val pass = binding.editTextPassword.text.toString()
        val objName = binding.editTextObjectName.text.toString().trim()
        val keyField = binding.editTextKeyField.text.toString().trim() // Read the key field

        val selectedActionId = binding.radioGroupActionType.checkedRadioButtonId
        val actionType = when (selectedActionId) {
            R.id.radioCreate -> VeevaActionType.CREATE
            R.id.radioUpdate -> VeevaActionType.UPDATE
            R.id.radioDelete -> VeevaActionType.DELETE
            else -> VeevaActionType.UPSERT
        }

        if (dns.isEmpty() || user.isEmpty() || pass.isEmpty() || objName.isEmpty()) {
            Toast.makeText(this, "DNS, User, Pass, and Object Name are required.", Toast.LENGTH_SHORT).show()
            return
        }

        if (actionType != VeevaActionType.CREATE && keyField.isEmpty()) {
            Toast.makeText(this, "Key Field is required for Update, Upsert, or Delete actions.", Toast.LENGTH_SHORT).show()
            return
        }

        uploadToVault(dns, user, pass, objName, actionType, keyField, fileToUploadUri!!)
    }

    private fun uploadToVault(dns: String, user: String, pass: String, objName: String, action: VeevaActionType, keyField: String?, fileUri: Uri) {
        showLoadingDialog()
        binding.textViewStatus.text = "Authenticating..."

        veevaApiUploader.authenticate(dns, user, pass) { authResult ->
            runOnUiThread {
                authResult.fold(
                    onSuccess = { sessionId ->
                        binding.textViewStatus.text = "Reading file..."
                        try {
                            val csvData = contentResolver.openInputStream(fileUri)?.bufferedReader().use { it?.readText() }
                            if (csvData.isNullOrEmpty()) {
                                hideLoadingDialog()
                                binding.textViewStatus.text = "Error: Failed to read file or file is empty."
                                return@runOnUiThread
                            }

                            binding.textViewStatus.text = "Uploading data..."
                            veevaApiUploader.uploadCsv(dns, sessionId, objName, csvData, action, keyField) { uploadResult ->
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

    private fun showLoadingDialog() {
        if (loadingDialog == null) {
            val builder = AlertDialog.Builder(this)
            val inflater = LayoutInflater.from(this)
            builder.setView(inflater.inflate(R.layout.dialog_loading, null))
            builder.setCancelable(false)
            loadingDialog = builder.create()
        }
        loadingDialog?.show()
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
    }
}
