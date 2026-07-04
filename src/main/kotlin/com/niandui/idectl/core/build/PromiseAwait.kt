package com.niandui.idectl.core.build

import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.concurrency.Promise
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Await an IntelliJ [Promise] without blocking (D-threading): the Promise resolves on the EDT, so
 * `blockingGet` would deadlock. We bridge its callbacks into the coroutine instead.
 */
suspend fun <T> Promise<T>.await(): T = suspendCancellableCoroutine { cont ->
    onSuccess { if (cont.isActive) cont.resume(it) }
    onError { if (cont.isActive) cont.resumeWithException(it) }
}
