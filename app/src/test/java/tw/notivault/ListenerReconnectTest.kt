package tw.notivault

import android.app.NotificationManager
import android.content.ComponentName
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationListenerService
import tw.notivault.capture.VaultNotificationListener

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = VaultApplication::class)
class ListenerReconnectTest {
    private val mainDispatcher = StandardTestDispatcher()
    private val scheduler = mainDispatcher.scheduler
    private lateinit var model: VaultViewModel

    @Before fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val application = ApplicationProvider.getApplicationContext<VaultApplication>()
        val manager = application.getSystemService(NotificationManager::class.java)
        val component = ComponentName(application, VaultNotificationListener::class.java)
        shadowOf(manager).setNotificationListenerAccessGranted(component, true)
        model = VaultViewModel(application)
    }

    @After fun tearDown() {
        if (::model.isInitialized) {
            model.viewModelScope.cancel()
            scheduler.runCurrent()
        }
        Dispatchers.resetMain()
    }

    @Test fun rebindTimesOutWhenTheSystemDoesNotConnect() {
        val requestsBefore = ShadowNotificationListenerService.getRebindRequestCount()
        model.reconnectListener()
        assertEquals(ListenerReconnectState.Connecting, model.listenerReconnect.value)
        model.reconnectListener()
        awaitOnMain { ShadowNotificationListenerService.getRebindRequestCount() > requestsBefore }
        assertEquals(requestsBefore + 1, ShadowNotificationListenerService.getRebindRequestCount())
        assertFalse(model.connected.value)

        scheduler.advanceUntilIdle()
        awaitOnMain { model.listenerReconnect.value == ListenerReconnectState.TimedOut }
        assertFalse(model.connected.value)
        assertNull(model.message.value)
    }

    @Test fun rebindSucceedsWhenTheListenerReceivesItsConnectedCallback() {
        val service = Robolectric.buildService(VaultNotificationListener::class.java).create()
        try {
            val requestsBefore = ShadowNotificationListenerService.getRebindRequestCount()
            model.reconnectListener()
            awaitOnMain { ShadowNotificationListenerService.getRebindRequestCount() > requestsBefore }
            assertEquals(ListenerReconnectState.Connecting, model.listenerReconnect.value)
            assertNull(model.message.value)

            service.get().onListenerConnected()
            awaitOnMain { model.message.value != null }
            assertTrue(model.connected.value)
            assertEquals(ListenerReconnectState.Idle, model.listenerReconnect.value)
            assertEquals("通知服務已連線", model.message.value)
        } finally {
            service.destroy()
        }
    }

    private fun awaitOnMain(condition: () -> Boolean) = runBlocking {
        // Let the real IO dispatcher finish without advancing the view model's timeout.
        withTimeout(5_000) {
            scheduler.runCurrent()
            while (!condition()) {
                delay(10)
                scheduler.runCurrent()
            }
        }
    }
}
