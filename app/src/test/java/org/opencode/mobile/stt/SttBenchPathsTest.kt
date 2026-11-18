package org.opencode.mobile.stt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SttBenchPathsTest {
    private val roots = listOf("/data/local/tmp", "/data/user/0/org.opencode.mobile.debug/files")

    @Test
    fun `клип из adb-каталога разрешён`() {
        assertTrue(SttBenchPaths.isAllowed("/data/local/tmp/bench/clip.wav", roots))
        assertTrue(SttBenchPaths.isAllowed("/data/local/tmp/clip.wav", roots))
    }

    @Test
    fun `файл в каталоге приложения разрешён`() {
        assertTrue(
            SttBenchPaths.isAllowed(
                "/data/user/0/org.opencode.mobile.debug/files/bench/clip.wav",
                roots,
            ),
        )
    }

    @Test
    fun `сам корень разрешён`() {
        assertTrue(SttBenchPaths.isAllowed("/data/local/tmp", roots))
    }

    @Test
    fun `соседний каталог с общим префиксом запрещён`() {
        assertFalse(SttBenchPaths.isAllowed("/data/local/tmp2/clip.wav", roots))
        assertFalse(
            SttBenchPaths.isAllowed(
                "/data/user/0/org.opencode.mobile.debug/files-private/clip.wav",
                roots,
            ),
        )
    }

    @Test
    fun `выход через две точки запрещён`() {
        assertFalse(SttBenchPaths.isAllowed("/data/local/tmp/../secrets/clip.wav", roots))
        assertFalse(SttBenchPaths.isAllowed("/data/local/tmp/bench/../../clip.wav", roots))
        assertFalse(
            SttBenchPaths.isAllowed(
                "/data/user/0/org.opencode.mobile.debug/files/../../shared_prefs/x.xml",
                roots,
            ),
        )
    }

    @Test
    fun `чужие каталоги запрещены`() {
        assertFalse(SttBenchPaths.isAllowed("/sdcard/Documents/OpencodeTerminal/AGENTS.md", roots))
        assertFalse(SttBenchPaths.isAllowed("/data/user/0/com.android.chrome/x.wav", roots))
        assertFalse(SttBenchPaths.isAllowed("/system/bin/sh", roots))
    }

    @Test
    fun `пустой и относительный путь запрещены`() {
        assertFalse(SttBenchPaths.isAllowed("", roots))
        assertFalse(SttBenchPaths.isAllowed("   ", roots))
        assertFalse(SttBenchPaths.isAllowed("clip.wav", roots))
        assertFalse(SttBenchPaths.isAllowed("/data/local/tmp/bench/clip.wav", emptyList()))
    }
}
