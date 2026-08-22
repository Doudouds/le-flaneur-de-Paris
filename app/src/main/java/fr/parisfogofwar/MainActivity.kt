package fr.parisfogofwar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import fr.parisfogofwar.presentation.ParisApp
import fr.parisfogofwar.presentation.theme.LeFlaneurTheme
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.parisfogofwar.presentation.AppViewModel
import fr.parisfogofwar.notifications.DiscoveryNotificationHelper
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val pendingDiscoveryMonumentId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingDiscoveryMonumentId.value = intent.getStringExtra(DiscoveryNotificationHelper.EXTRA_MONUMENT_ID)
        setContent {
            val notificationMonumentId by pendingDiscoveryMonumentId.collectAsStateWithLifecycle()
            LeFlaneurTheme {
                ParisApp(
                    viewModel = hiltViewModel<AppViewModel>(),
                    notificationMonumentId = notificationMonumentId,
                    onNotificationHandled = { pendingDiscoveryMonumentId.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDiscoveryMonumentId.value = intent.getStringExtra(DiscoveryNotificationHelper.EXTRA_MONUMENT_ID)
    }
}
