package com.rwai.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.ContextCompat
import com.rwai.agent.core.AgentNotifications
import com.rwai.agent.core.IncomingShare
import com.rwai.agent.ui.RwRoot
import com.rwai.agent.ui.RwTheme

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)
    private val voiceTick = mutableIntStateOf(0)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* результат отражается в окне разрешений */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleIntent(intent)
        setContent {
            RwTheme {
                RwRoot(resumeTick.intValue, voiceTick.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(EXTRA_VOICE, false) || intent.action == Intent.ACTION_ASSIST) {
            voiceTick.intValue++
        }
        if (intent.type?.startsWith("image/") == true) {
            val uris: List<Uri> = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(
                    if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM),
                )
                Intent.ACTION_SEND_MULTIPLE ->
                    (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
                else -> emptyList()
            }
            if (uris.isNotEmpty()) IncomingShare.uris.value = uris
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        AgentNotifications.clearResult(this)
    }

    companion object {
        const val EXTRA_VOICE = "com.rwai.agent.extra.VOICE"
    }
}
