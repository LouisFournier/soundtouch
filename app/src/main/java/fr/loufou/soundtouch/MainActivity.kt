package fr.loufou.soundtouch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import fr.loufou.soundtouch.ui.SoundTouchTheme
import fr.loufou.soundtouch.ui.SpeakersScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: SpeakersViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // On n'interroge les enceintes que lorsque l'appli est à l'écran.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watch() }
        }
        setContent {
            SoundTouchTheme {
                SpeakersScreen(viewModel)
            }
        }
    }
}
