package com.dmarket.p2p.tracker.runtime

import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidTrackerTest {
    @Test
    fun a_failure_in_the_tracker_scope_does_not_reach_the_uncaught_exception_handler() = runBlocking {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        var uncaught: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught = e }
        try {
            AndroidTracker.scope.launch { error("host linkedAccount() threw") }.join()
            assertNull(uncaught)
            assertTrue(AndroidTracker.scope.isActive, "the scope survives for the next start")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
