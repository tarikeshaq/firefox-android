/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.push

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import mozilla.appservices.push.*
import mozilla.components.concept.base.crash.CrashReporting
import mozilla.components.concept.push.EncryptedPushMessage
import mozilla.components.concept.push.PushError
import mozilla.components.concept.push.PushService
import mozilla.components.feature.push.AutoPushFeature.Companion.PREFERENCE_NAME
import mozilla.components.feature.push.AutoPushFeature.Companion.PREF_TOKEN
import mozilla.components.support.test.any
import mozilla.components.support.test.mock
import mozilla.components.support.test.nullable
import mozilla.components.support.test.robolectric.testContext
import mozilla.components.support.test.rule.MainCoroutineRule
import mozilla.components.support.test.rule.runTestOnMain
import mozilla.components.support.test.whenever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class AutoPushFeatureTest {

    @get:Rule
    val coroutinesTestRule = MainCoroutineRule()

    private val connection: PushManagerInterface = mock()

    @Test
    fun `initialize starts push service`() {
        val service: PushService = mock()
        val config = PushConfig("push-test")
        val feature = AutoPushFeature(testContext, service, config)
        feature.connection = connection

        feature.initialize()

        verify(service).start(testContext)

        verifyNoMoreInteractions(service)
    }

    @Test
    fun `updateToken not called if no token in prefs`() = runTestOnMain {
        val feature = AutoPushFeature(testContext, mock(), mock(), coroutineContext)
        feature.connection = connection

        verify(connection, never()).update(anyString())
    }

    @Test
    fun `updateToken called if token is in prefs`() = runTestOnMain {
        preference(testContext).edit().putString(PREF_TOKEN, "token").apply()

        val feature = AutoPushFeature(
            testContext,
            mock(),
            mock(),
            coroutineContext = coroutineContext,
        )

        feature.connection = connection

        feature.initialize()

        verify(connection).update("token")
    }

    @Test
    fun `shutdown stops service and unsubscribes all`() = runTestOnMain {
        val service: PushService = mock()

        AutoPushFeature(testContext, service, mock(), coroutineContext).also {
            it.connection = connection
            it.shutdown()
        }

        verify(connection).unsubscribeAll()
    }

    @Test
    fun `onNewToken updates connection and saves pref`() = runTestOnMain {
        val feature = AutoPushFeature(testContext, mock(), mock(), coroutineContext)
        feature.connection = connection

        whenever(connection.subscribe(anyString(), nullable())).thenReturn(mock())

        feature.onNewToken("token")

        verify(connection).update("token")

        val pref = preference(testContext).getString(PREF_TOKEN, null)
        assertNotNull(pref)
        assertEquals("token", pref)
    }

    @Test
    fun `onMessageReceived decrypts message and notifies observers`() = runTestOnMain {
        val encryptedMessage: Map<String, String> = mock()
        val owner: LifecycleOwner = mock()
        val lifecycle: Lifecycle = mock()
        val observer: AutoPushFeature.Observer = mock()
        whenever(owner.lifecycle).thenReturn(lifecycle)
        whenever(lifecycle.currentState).thenReturn(Lifecycle.State.STARTED)
        whenever(connection.decrypt(any()))
            .thenReturn(null) // If we get null, we shouldn't notify observers.
            .thenReturn(DecryptResponse(result = "test".toByteArray().asList(), scope = "testScope"))

        val feature = AutoPushFeature(testContext, mock(), mock(), coroutineContext)
        feature.connection = connection
        feature.register(observer)

        feature.onMessageReceived(encryptedMessage)

        verify(observer, never()).onMessageReceived("testScope", "test".toByteArray())

        feature.onMessageReceived(encryptedMessage)

        verify(observer).onMessageReceived("testScope", "test".toByteArray())
    }

    @Test
    fun `subscribe calls native layer and notifies observers`() = runTestOnMain {
        val connection = TestPushConnection()

        var invoked = false
        val feature = AutoPushFeature(testContext, mock(), mock(), coroutineContext)
        feature.connection = connection

        feature.subscribe("testScope") {
            invoked = true
        }

        assertTrue(invoked)
    }

    @Test
    fun `subscribe invokes error callback`() = runTestOnMain {
        val subscription: AutoPushSubscription = mock()
        var invoked = false
        var errorInvoked = false
        val feature = AutoPushFeature(testContext, mock(), mock(), coroutineContext)
        feature.connection = connection

        feature.subscribe(
            scope = "testScope",
            onSubscribeError = {
                errorInvoked = true
            },
            onSubscribe = {
                invoked = true
            },
        )

        assertFalse(invoked)
        assertFalse(errorInvoked)

        whenever(connection.subscribe(anyString(), nullable())).thenAnswer { throw PushApiException.InternalException("") }
        whenever(subscription.scope).thenReturn("testScope")

        feature.subscribe(
            scope = "testScope",
            onSubscribeError = {
                errorInvoked = true
            },
            onSubscribe = {
                invoked = true
            },
        )

        assertFalse(invoked)
        assertTrue(errorInvoked)
    }

    @Test
    fun `unsubscribe calls native layer and notifies observers`() = runTestOnMain {
        var invoked = false
        var errorInvoked = false

        val feature = AutoPushFeature(testContext, mock(), mock(), coroutineContext)
        feature.connection = connection
        feature.unsubscribe(
            scope = "testScope",
            onUnsubscribeError = {
                errorInvoked = true
            },
            onUnsubscribe = {
                invoked = true
            },
        )

        assertTrue(invoked)
        assertFalse(errorInvoked)
    }

    @Test
    fun `unsubscribe invokes error callback on native exception`() = runTestOnMain {
        val feature = AutoPushFeature(testContext, mock(), mock(), coroutineContext)
        feature.connection = connection
        var invoked = false
        var errorInvoked = false

        whenever(connection.unsubscribe(anyString())).thenAnswer { throw PushApiException.InternalException("") }

        feature.unsubscribe(
            scope = "testScope",
            onUnsubscribeError = {
                errorInvoked = true
            },
            onUnsubscribe = {
                invoked = true
            },
        )

        assertFalse(invoked)
        assertTrue(errorInvoked)
    }

    @Test
    fun `getSubscription returns null when there is no subscription`() = runTestOnMain {
        val feature = AutoPushFeature(testContext, mock(), mock(), coroutineContext)
        feature.connection = connection
        var invoked = false


        whenever(connection.getSubscription(anyString())).thenReturn(null)

        feature.getSubscription(
            scope = "testScope",
            appServerKey = null,
        ) {
            invoked = it == null
        }

        assertTrue(invoked)
    }

    @Test
    fun `getSubscription invokes subscribe when there is a subscription`() = runTestOnMain {
        val feature = AutoPushFeature(testContext, mock(), mock(), coroutineContext)
        feature.connection = connection
        var invoked = false

        whenever(connection.getSubscription(anyString())).thenReturn(
            SubscriptionResponse(
                channelId = "cid",
                subscriptionInfo = SubscriptionInfo(
                    endpoint = "endpoint",
                    keys = KeyInfo(
                        auth = "auth",
                        p256dh = "p256dh"
                    )
                )
            )
        )

        feature.getSubscription(
            scope = "testScope",
            appServerKey = null,
        ) {
            invoked = it != null
        }

        assertTrue(invoked)
    }

    @Test
    fun `forceRegistrationRenewal deletes pref and calls service`() = runTestOnMain {
        val service: PushService = mock()
        val feature = AutoPushFeature(testContext, service, mock(), coroutineContext)
        feature.connection = connection

        feature.renewRegistration()

        verify(service).deleteToken()
        verify(service).start(testContext)

        val pref = preference(testContext).getString(PREF_TOKEN, null)
        assertNull(pref)
    }

    @Test
    fun `verifyActiveSubscriptions notifies observers`() = runTestOnMain {
        val connection: PushManagerInterface = spy(TestPushConnection())
        val owner: LifecycleOwner = mock()
        val lifecycle: Lifecycle = mock()
        val observers: AutoPushFeature.Observer = mock()
        val feature = AutoPushFeature(testContext, mock(), mock(), coroutineContext)
        feature.connection = connection
        whenever(owner.lifecycle).thenReturn(lifecycle)
        whenever(lifecycle.currentState).thenReturn(Lifecycle.State.STARTED)

        feature.register(observers)

        // When there are NO subscription updates, observers should not be notified.
        feature.verifyActiveSubscriptions()

        verify(observers, never()).onSubscriptionChanged(any())

        // When there are no subscription updates, observers should not be notified.
        whenever(connection.verifyConnection()).thenReturn(emptyList())
        feature.verifyActiveSubscriptions()

        verify(observers, never()).onSubscriptionChanged(any())

        // When there are subscription updates, observers should be notified.
        whenever(connection.verifyConnection()).thenReturn(listOf(PushSubscriptionChanged(scope = "scope", channelId = "1246")))
        feature.verifyActiveSubscriptions()

        verify(observers).onSubscriptionChanged("scope")
    }

    @Test
    fun `new FCM token executes verifyActiveSubscription`() = runTestOnMain {
        val feature = spy(
            AutoPushFeature(
                context = testContext,
                service = mock(),
                config = mock(),
                coroutineContext = coroutineContext,
            ),
        )
        feature.connection = connection

        feature.initialize()
        // no token yet so should not have even tried.
        verify(feature, never()).verifyActiveSubscriptions()

        // new token == "check now"
        feature.onNewToken("test-token")
        verify(feature).verifyActiveSubscriptions()
    }

    @Test
    fun `verification doesn't happen until we've got the token`() = runTestOnMain {
        val feature = spy(
            AutoPushFeature(
                context = testContext,
                service = mock(),
                config = mock(),
                coroutineContext = coroutineContext,
            ),
        )

        feature.connection = connection

        feature.initialize()

        verify(feature, never()).verifyActiveSubscriptions()
    }

    @Test
    fun `crash reporter is notified of errors`() = runTestOnMain {
        val native: PushManagerInterface = TestPushConnection()
        val crashReporter: CrashReporting = mock()
        val feature = AutoPushFeature(
            context = testContext,
            service = mock(),
            config = mock(),
            coroutineContext = coroutineContext,
            crashReporter = crashReporter,
        )
        feature.connection = native

        feature.onError(PushError.Rust(PushError.MalformedMessage("Bad things happened!")))

        verify(crashReporter).submitCaughtException(any<PushError.Rust>())
    }

    @Test
    fun `non-fatal errors trigger verifyConnection`() = runTestOnMain {
        val crashReporter: CrashReporting = mock()
        val feature = AutoPushFeature(
            context = testContext,
            service = mock(),
            config = mock(),
            coroutineContext = coroutineContext,
            crashReporter = crashReporter,
        )

        feature.connection = connection

        whenever(connection.unsubscribe(any())).thenAnswer { throw PushApiException.UaidNotRecognizedException(
            "test"
        ) }
        feature.unsubscribe("123") {}

        verify(crashReporter, never()).submitCaughtException(any<PushError.Rust>())
        verify(connection).verifyConnection(true)
    }

    @Test
    fun `only fatal errors are reported`() = runTestOnMain {
        val crashReporter: CrashReporting = mock()
        val feature = AutoPushFeature(
            context = testContext,
            service = mock(),
            config = mock(),
            coroutineContext = coroutineContext,
            crashReporter = crashReporter,
        )

        feature.connection = connection

        whenever(connection.unsubscribe(any())).thenAnswer { throw PushApiException.InternalException("") }

        feature.unsubscribe("123") {}

        verify(crashReporter).submitCaughtException(any<PushError.Rust>())
    }

    companion object {
        private fun preference(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)
        }
    }

    class TestPushConnection() : PushManagerInterface {

        override fun unsubscribe(scope: String) = Unit

        override fun unsubscribeAll(): Unit = Unit

        override fun decrypt(
            payload: Map<String, String>
        ): DecryptResponse {
            return DecryptResponse(result = listOf(), scope = "testScope")
        }

        override fun getSubscription(scope: String): SubscriptionResponse? {
            return null
        }

        override fun subscribe(
            scope: String,
            appServerSey: String?,
        ): SubscriptionResponse {
            return SubscriptionResponse(
                channelId = "test-cid",
                subscriptionInfo = SubscriptionInfo(
                    endpoint = "https://foo",
                    keys = KeyInfo(auth = "auth", p256dh = "p256dh")
                ),
            )
        }

        override fun update(registrationToken: String) = Unit
        override fun verifyConnection(forceVerify: Boolean): List<PushSubscriptionChanged> = emptyList()
    }
}
