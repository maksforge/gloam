package app.gloam.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gloam.MainApplication
import app.gloam.data.AppPreferences
import app.gloam.data.ThemeMode
import app.gloam.theme.applyThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val launcherCompact: Boolean = true,
    val controlsBubble: Boolean = true,
)

class SettingsViewModel(
    private val preferences: AppPreferences,
) : ViewModel() {
    val state: StateFlow<SettingsUiState> =
        combine(
            preferences.themeMode,
            preferences.launcherCompact,
            preferences.controlsBubble,
        ) { theme, launcherCompact, controlsBubble ->
            SettingsUiState(theme, launcherCompact, controlsBubble)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /**
     * **Both halves, and in this order.**
     *
     * [applyThemeMode] moves the *window* — the background painted before Compose composes, and the
     * system-bar scrim — which Compose cannot reach. Writing the preference alone repaints the app
     * and leaves the window following the phone. Applying it alone repaints the window and forgets
     * the choice on the next cold start. See `theme/NightMode.kt`.
     */
    fun setThemeMode(mode: ThemeMode) {
        applyThemeMode(mode)
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    /**
     * Move the launcher tap to the compact controls, or back.
     *
     * The write is all of it: `MainApplication` holds the live value for `MainActivity` to read
     * before its first frame, and it is collecting this same `Flow`, so nothing here has to tell it.
     */
    fun setLauncherCompact(enabled: Boolean) {
        viewModelScope.launch { preferences.setLauncherCompact(enabled) }
    }

    fun setControlsBubble(enabled: Boolean) {
        viewModelScope.launch { preferences.setControlsBubble(enabled) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = this[APPLICATION_KEY] as MainApplication
                    // Preferences, not the container: Settings is reachable while the schema gate
                    // is open, and forcing the container here would be forcing Room.
                    SettingsViewModel(app.preferences)
                }
            }
    }
}
