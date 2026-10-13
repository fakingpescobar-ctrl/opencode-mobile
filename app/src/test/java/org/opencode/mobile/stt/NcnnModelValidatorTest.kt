package org.opencode.mobile.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Contract between the validator and the downloader manifest.
 *
 * The load-bearing test is the first one: if the validator wants MORE
 * files than the downloader ships, the set never becomes ready and
 * recognition never works. That is exactly what happened with the
 * encoder - both the int8 and the fp32 pair were listed as missing
 * (15 files) while only int8 is shipped (13 files), and the user saw
 * "missing 15 of 13".
 */
class NcnnModelValidatorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = tmp.newFolder("model")
    }

    private fun write(name: String) {
        File(dir, name).writeText("x")
    }

    private fun fillAll() {
        NcnnModelDownloader.FILE_NAMES.forEach { write(it) }
    }

    @Test
    fun emptyDirWantsExactlyAsManyFilesAsTheDownloaderShips() {
        val check = NcnnModelValidator.checkModelDir(dir)
        assertEquals(NcnnModelDownloader.FILE_COUNT, check.missing.size)
    }

    @Test
    fun fullyFilledDirIsReady() {
        fillAll()
        val check = NcnnModelValidator.checkModelDir(dir)
        assertTrue(check.missing.toString(), check.ok)
    }

    @Test
    fun aSingleMissingFileIsTheOnlyComplaint() {
        fillAll()
        assertTrue(File(dir, "whisper_turbo_decoder.ncnn.param").delete())
        val check = NcnnModelValidator.checkModelDir(dir)
        assertEquals(1, check.missing.size)
        assertEquals("whisper_turbo_decoder.ncnn.param", check.missing.first())
    }

    @Test
    fun int8EncoderAloneSatisfiesTheEncoderRequirement() {
        fillAll()
        // The fp32 pair is not shipped, so it must never be demanded.
        assertTrue(NcnnModelValidator.checkModelDir(dir).ok)
    }

    @Test
    fun encoderVariantFollowsWhatIsOnDisk() {
        assertEquals(NcnnModelValidator.EncoderVariant.NONE, NcnnModelValidator.encoderVariant(dir))
        write("whisper_turbo_encoder_int8.ncnn.param")
        assertEquals(NcnnModelValidator.EncoderVariant.NONE, NcnnModelValidator.encoderVariant(dir))
        write("whisper_turbo_encoder_int8.ncnn.bin")
        assertEquals(NcnnModelValidator.EncoderVariant.INT8, NcnnModelValidator.encoderVariant(dir))
    }
}
