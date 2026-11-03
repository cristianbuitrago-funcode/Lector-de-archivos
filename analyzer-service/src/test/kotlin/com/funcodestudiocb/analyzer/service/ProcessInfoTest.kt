package com.funcodestudiocb.analyzer.service

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessInfoTest {

    @Test
    fun rangosDeUidAislados() {
        assertTrue(ProcessInfo.isIsolatedUid(99_000))
        assertTrue(ProcessInfo.isIsolatedUid(99_999))
        assertTrue(ProcessInfo.isIsolatedUid(10 * 100_000 + 99_123)) // usuario secundario
        assertTrue(ProcessInfo.isIsolatedUid(90_000))
        assertFalse(ProcessInfo.isIsolatedUid(10_123)) // app normal
        assertFalse(ProcessInfo.isIsolatedUid(1_000)) // sistema
    }
}
