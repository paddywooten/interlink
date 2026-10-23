package com.interlink.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.interlink.app.core.Prefs
import com.interlink.app.ui.InterLinkTheme
import com.interlink.app.ui.AppUi

/** Single activity: Compose UI over the link service. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        setContent {
            InterLinkTheme {
                AppUi(prefs)
            }
        }
    }
}
