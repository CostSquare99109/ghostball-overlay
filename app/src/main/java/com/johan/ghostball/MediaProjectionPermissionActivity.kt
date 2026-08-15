package com.johan.ghostball

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * Translucent trampoline that asks the user for screen-capture consent on
 * behalf of [OverlayService] (a Service cannot show the consent dialog
 * itself). Forwards the result to the service and finishes immediately.
 */
class MediaProjectionPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_CAPTURE) {
            finish()
            return
        }
        val forward = Intent(this, OverlayService::class.java)
            .setAction(OverlayService.ACTION_PROJECTION_GRANTED)
            .putExtra(OverlayService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
        if (resultCode == RESULT_OK && data != null) {
            forward.putExtra(OverlayService.EXTRA_PROJECTION_RESULT_INTENT, data)
        }
        ContextCompat.startForegroundService(this, forward)
        finish()
    }

    companion object {
        private const val REQ_CAPTURE = 4243
    }
}