package com.whisper.update

import org.bouncycastle.util.encoders.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateManifestTest {

    /** Signé par openssl avec la vraie clé développeur, comme le fait tools/make-update.sh. */
    private val raw = ("{\"app\":\"com.whospair\",\"code\":2,\"name\":\"1.1\",\"size\":5," +
        "\"sha256\":\"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824\",\"date\":1790000000000}")
        .toByteArray(Charsets.UTF_8)
    private val signature = Hex.decode(
        "acc75790f41564484896fee25c417ca718ad06e96721490d22e4684ac9534236" +
            "3a1eb4e98d97bcf3903b6b29d415b799977591a7b15d326d3ded4c2110687208"
    )

    @Test
    fun opensslSignatureIsAccepted() {
        val manifest = UpdateManifest.verify(raw, signature)!!
        assertEquals(2L, manifest.versionCode)
        assertEquals("1.1", manifest.versionName)
        assertEquals(5L, manifest.size)
        assertArrayEquals(UpdatePackage.sha256("hello".toByteArray()), manifest.sha256)
    }

    @Test
    fun tamperedManifestIsRefused() {
        val forged = String(raw, Charsets.UTF_8).replace("\"code\":2", "\"code\":9").toByteArray(Charsets.UTF_8)
        assertNull(UpdateManifest.verify(forged, signature))
        val badSig = signature.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNull(UpdateManifest.verify(raw, badSig))
    }

    @Test
    fun packageRoundTrip() {
        val apk = "hello".toByteArray()
        val bytes = "WUP1".toByteArray() + byteArrayOf(0, 0, (raw.size shr 8).toByte(), raw.size.toByte()) + raw + signature + apk
        val parsed = UpdatePackage.parse(bytes)!!
        assertArrayEquals(apk, parsed.apk)
        // APK remplacé : empreinte différente, refusé.
        val swapped = bytes.copyOf().also { it[it.size - 1] = 'x'.code.toByte() }
        assertNull(UpdatePackage.parse(swapped))
    }

    @Test
    fun dataFrameRoundTrip() {
        val chunk = ByteArray(1000) { it.toByte() }
        val frame = UpdateProtocol.data(2, 123_456, chunk, 700)
        val data = UpdateProtocol.dataOf(frame)!!
        assertEquals(2L, data.code)
        assertEquals(123_456L, data.offset)
        assertEquals(700, data.length)
        assertArrayEquals(chunk.copyOf(700), data.payload.copyOfRange(data.start, data.payload.size))
        assertNull(UpdateProtocol.dataOf("{\"v\":1}".toByteArray()))
    }
}
