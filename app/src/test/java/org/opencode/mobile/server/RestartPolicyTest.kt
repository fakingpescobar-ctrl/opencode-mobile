package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartPolicyTest {
    private val policy = RestartPolicy(MAX_ATTEMPTS, BASE_DELAY_MS, CAP_DELAY_MS, SHIFT_CAP)

    @Test
    fun `backoff растёт вдвое до потолка`() {
        assertEquals(BASE_DELAY_MS shl 1, policy.backoffMs(1))
        assertEquals(BASE_DELAY_MS shl 2, policy.backoffMs(2))
        assertEquals(BASE_DELAY_MS shl 3, policy.backoffMs(3))
    }

    @Test
    fun `backoff упирается в потолок`() {
        assertEquals(CAP_DELAY_MS, policy.backoffMs(SHIFT_CAP))
        assertEquals(CAP_DELAY_MS, policy.backoffMs(MAX_ATTEMPTS))
        assertEquals(CAP_DELAY_MS, policy.backoffMs(SHIFT_CAP * SHIFT_CAP))
    }

    @Test
    fun `нулевой счётчик даёт паузу первого фейла`() {
        assertEquals(BASE_DELAY_MS shl 1, policy.backoffMs(0))
        assertEquals(BASE_DELAY_MS shl 1, policy.backoffMs(-1))
    }

    @Test
    fun `лимит достигнут на maxAttempts-м отказе`() {
        assertFalse(policy.isLimitReached(0))
        assertFalse(policy.isLimitReached(MAX_ATTEMPTS - 1))
        assertTrue(policy.isLimitReached(MAX_ATTEMPTS))
        assertTrue(policy.isLimitReached(MAX_ATTEMPTS + 1))
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val BASE_DELAY_MS = 1_000L
        const val CAP_DELAY_MS = 15_000L
        const val SHIFT_CAP = 4
    }
}
