package sh.haven.app.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import sh.haven.core.ui.navigation.Screen
import javax.inject.Inject

/**
 * Holds the bottom-nav / pager selection as the single source of truth, hoisted
 * OUT of [HavenNavHost]'s composition.
 *
 * Why a ViewModel and not `remember`/`rememberSaveable` inside the nav host:
 * when an app window goes to Picture-in-Picture, `MainActivity` swaps the whole
 * `HavenNavHost` out for a full-bleed PiP branch, so the nav host *leaves the
 * composition*. On PiP-exit it is composed fresh and any `remember`/
 * `rememberSaveable` it owned is gone (a plain recomposition triggers no
 * `performSave`, so even saveable state is discarded on a leave/re-enter) — the
 * selection re-initialised to page 0 (Connections) and the user lost their place.
 *
 * A ViewModel is scoped to the Activity's `ViewModelStore`, so it is NOT cleared
 * when the composable leaves the tree, survives configuration change, and —
 * because the route is persisted in [SavedStateHandle] — survives process death.
 * On PiP-exit the fresh nav host reads the retained selection and the render
 * effect scrolls the (reset) pager back to it.
 */
@HiltViewModel
class NavStateViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The currently-selected screen. Defaults to Connections on a cold start. */
    val selectedScreen: StateFlow<Screen> =
        savedStateHandle.getStateFlow(KEY_ROUTE, Screen.Connections.route)
            .map { route -> screenForRoute(route) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, screenForRoute(currentRoute()))

    /** Set the selection. Idempotent; persisted so it survives process death. */
    fun select(screen: Screen) {
        savedStateHandle[KEY_ROUTE] = screen.route
    }

    private fun currentRoute(): String =
        savedStateHandle[KEY_ROUTE] ?: Screen.Connections.route

    private fun screenForRoute(route: String): Screen =
        Screen.entries.firstOrNull { it.route == route } ?: Screen.Connections

    companion object {
        private const val KEY_ROUTE = "selected_screen_route"
    }
}
