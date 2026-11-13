package sh.haven.app.navigation

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import sh.haven.core.ui.navigation.Screen

@OptIn(ExperimentalCoroutinesApi::class)
class NavStateViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val routeKey = "selected_screen_route"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `defaults to Connections on cold start`() = runTest {
        val vm = NavStateViewModel(SavedStateHandle())
        assertEquals(Screen.Connections, vm.selectedScreen.value)
    }

    @Test
    fun `select updates the selection`() = runTest {
        val vm = NavStateViewModel(SavedStateHandle())
        vm.select(Screen.Terminal)
        advanceUntilIdle()
        assertEquals(Screen.Terminal, vm.selectedScreen.value)
    }

    @Test
    fun `restores persisted route across process death`() {
        // A SavedStateHandle seeded with a prior route simulates a process-death
        // restore: the selection must come back without needing a fresh select().
        val handle = SavedStateHandle(mapOf(routeKey to Screen.Sftp.route))
        val vm = NavStateViewModel(handle)
        assertEquals(Screen.Sftp, vm.selectedScreen.value)
    }

    @Test
    fun `unknown route falls back to Connections`() {
        val handle = SavedStateHandle(mapOf(routeKey to "bogus-route"))
        val vm = NavStateViewModel(handle)
        assertEquals(Screen.Connections, vm.selectedScreen.value)
    }

    @Test
    fun `select persists the route to the handle`() {
        val handle = SavedStateHandle()
        val vm = NavStateViewModel(handle)
        vm.select(Screen.Keys)
        assertEquals(Screen.Keys.route, handle.get<String>(routeKey))
    }
}
