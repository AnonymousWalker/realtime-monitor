package com.realtimemonitor.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WavHeaderTest {

    @Test
    fun headerIsCorrectSize() {
        val header = WavHeader.create(sampleRate = 16000, channels = 1, bitsPerSample = 16)
        assertEquals(44, header.size)
    }

    @Test
    fun riffChunkIsCorrect() {
        val header = WavHeader.create(sampleRate = 16000, channels = 1, bitsPerSample = 16)
        assertArrayEquals(
            byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()),
            header.sliceArray(0..3)
        )
    }

    @Test
    fun waveFormatIsCorrect() {
        val header = WavHeader.create(sampleRate = 16000, channels = 1, bitsPerSample = 16)
        assertArrayEquals(
            byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()),
            header.sliceArray(8..11)
        )
    }

    @Test
    fun fmtChunkIsCorrect() {
        val header = WavHeader.create(sampleRate = 16000, channels = 1, bitsPerSample = 16)
        assertArrayEquals(
            byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()),
            header.sliceArray(12..15)
        )
    }

    @Test
    fun dataChunkIsCorrect() {
        val header = WavHeader.create(sampleRate = 16000, channels = 1, bitsPerSample = 16)
        assertArrayEquals(
            byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()),
            header.sliceArray(36..39)
        )
    }

    @Test
    fun audioFormatIsPcm() {
        val header = WavHeader.create(sampleRate = 16000, channels = 1, bitsPerSample = 16)
        val format = (header[20].toInt() and 0xFF) or ((header[21].toInt() and 0xFF) shl 8)
        assertEquals(1, format) // PCM = 1
    }

    @Test
    fun channelCountIsCorrect() {
        val header = WavHeader.create(sampleRate = 44100, channels = 2, bitsPerSample = 16)
        val channels = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
        assertEquals(2, channels)
    }

    @Test
    fun sampleRateIsCorrect() {
        val header = WavHeader.create(sampleRate = 44100, channels = 1, bitsPerSample = 16)
        val sampleRate = (header[24].toInt() and 0xFF) or
                ((header[25].toInt() and 0xFF) shl 8) or
                ((header[26].toInt() and 0xFF) shl 16) or
                ((header[27].toInt() and 0xFF) shl 24)
        assertEquals(44100, sampleRate)
    }

    @Test
    fun byteRateIsCorrect() {
        val header = WavHeader.create(sampleRate = 16000, channels = 1, bitsPerSample = 16)
        val byteRate = (header[28].toInt() and 0xFF) or
                ((header[29].toInt() and 0xFF) shl 8) or
                ((header[30].toInt() and 0xFF) shl 16) or
                ((header[31].toInt() and 0xFF) shl 24)
        assertEquals(32000, byteRate) // 16000 * 1 * 16/8
    }

    @Test
    fun dataSizeIsMaxInt() {
        val header = WavHeader.create(sampleRate = 16000, channels = 1, bitsPerSample = 16)
        val dataSize = (header[40].toInt() and 0xFF) or
                ((header[41].toInt() and 0xFF) shl 8) or
                ((header[42].toInt() and 0xFF) shl 16) or
                ((header[43].toInt() and 0xFF) shl 24)
        assertEquals(Int.MAX_VALUE, dataSize)
    }
}
