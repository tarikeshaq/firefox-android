/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 *  License, v. 2.0. If a copy of the MPL was not distributed with this
 *  file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package mozilla.components.feature.push.ext

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import mozilla.appservices.push.PushApiException.UaidNotRecognizedException
import mozilla.appservices.push.PushApiException.RecordNotFoundException
import mozilla.appservices.push.PushApiException.InternalException
import org.junit.Test

@ExperimentalCoroutinesApi
class CoroutineScopeKtTest {

    @Test(expected = InternalException::class)
    fun `launchAndTry triggers errorBlock on push error`() = runTest {
        CoroutineScope(coroutineContext).launchAndTry(
            errorBlock = { throw InternalException("unit test") },
            block = { throw UaidNotRecognizedException("") },
        )
    }

    @Test(expected = ArithmeticException::class)
    fun `launchAndTry does not catch original exception if not push error`() = runTest {
        CoroutineScope(coroutineContext).launchAndTry(
            errorBlock = { throw InternalException("unit test") },
            block = { throw ArithmeticException() },
        )
    }

    @Test(expected = UaidNotRecognizedException::class)
    fun `launchAndTry continues to re-throw if error block doesn't throw`() = runTest {
        CoroutineScope(coroutineContext).launchAndTry(
            errorBlock = { assert(true) },
            block = { throw UaidNotRecognizedException("") },
        )
    }


    @Test
    fun `launchAndTry should NOT throw on recoverable Rust exceptions`() = runTest {
        CoroutineScope(coroutineContext).launchAndTry(
            { throw UaidNotRecognizedException("should not fail test") },
            { assert(true) },
        )

        CoroutineScope(coroutineContext).launchAndTry(
            { throw RecordNotFoundException("should not fail test") },
            { assert(true) },
        )
    }
}
