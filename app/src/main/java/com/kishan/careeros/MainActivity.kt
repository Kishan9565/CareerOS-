package com.kishan.careeros

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.kishan.careeros.presentation.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint class MainActivity: ComponentActivity() {
    private val dashboard: DashboardViewModel by viewModels()
    private val discovery: DiscoveryViewModel by viewModels()
    private val intelligence: IntelligenceViewModel by viewModels()
    private val profile: ProfileViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receiveShare(intent)
        enableEdgeToEdge()
        setContent { CareerApp(dashboard, profile, discovery, intelligence) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShare(intent)
    }
    private fun receiveShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                ?: return
            discovery.receiveShare(text, intent.getStringExtra(Intent.EXTRA_SUBJECT))
        }
    }
}
