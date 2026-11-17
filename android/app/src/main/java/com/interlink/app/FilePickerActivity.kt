package com.interlink.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.interlink.app.handlers.FileHandler

/**
 * Translucent trampoline for the desktop's "choose a folder on the phone"
 * request. A foreground service cannot use startActivityForResult itself, so it
 * starts this activity, which asks the system for a document tree and hands the
 * answer (including cancellation) back to FileHandler.
 */
class FilePickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        try {
            startActivityForResult(pick, REQUEST_TREE)
        } catch (e: Exception) {
            FileHandler.onTreeResult(this, null)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_TREE) {
            finish()
            return
        }
        FileHandler.onTreeResult(this, if (resultCode == RESULT_OK) data?.data else null)
        finish()
    }

    private companion object {
        const val REQUEST_TREE = 1
    }
}
