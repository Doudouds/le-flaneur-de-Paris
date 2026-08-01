package fr.parisfogofwar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import fr.parisfogofwar.presentation.ParisApp
import fr.parisfogofwar.presentation.theme.LeFlaneurTheme
import androidx.hilt.navigation.compose.hiltViewModel
import fr.parisfogofwar.presentation.AppViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LeFlaneurTheme { ParisApp(hiltViewModel<AppViewModel>()) } }
    }
}
