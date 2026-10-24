package com.interlink.app.mirror

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.interlink.app.core.Bus
import com.interlink.app.core.objOf

/**
 * Transparent trampoline that asks the user for screen-capture consent, then
 * hands the grant to [ScreenMirrorService]. MediaProjection is only ever started
 * after this explicit user approval (Android requirement + privacy).
 *
 * Launched by [com.interlink.app.service.LinkService] when the laptop requests
 * `mirror.start`, or from the phone UI.
 */
class ProjectionActivity : ComponentActivity() {

    private var width = 720
    private var fps = 8

    private val consent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val svc = Intent(this, ScreenMirrorService::class.java)
                    .putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(ScreenMirrorService.EXTRA_RESULT_DATA, result.data)
                    .putExtra(ScreenMirrorService.EXTRA_WIDTH, width)
                    .putExtra(ScreenMirrorService.EXTRA_FPS, fps)
                startForegroundService(svc)
                Bus.push("mirror.state", objOf("active" to true, "pending" to true))
            } else {
                Bus.push("mirror.state", objOf("active" to false, "reason" to "denied"))
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        width = intent?.getIntExtra(ScreenMirrorService.EXTRA_WIDTH, 720) ?: 720
        fps = intent?.getIntExtra(ScreenMirrorService.EXTRA_FPS, 8) ?: 8
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        consent.launch(mgr.createScreenCaptureIntent())
    }
}
