package com.whisper.transport

import com.whisper.crypto.CryptoManager
import org.bouncycastle.util.encoders.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class TorLinkTest {

    private val crypto = CryptoManager()
    private val alice = crypto.generateIdentity()
    private val bob = crypto.generateIdentity()

    @Test fun `trame aller-retour`() {
        val buffer = ByteArrayOutputStream()
        TorLink.writeFrame(DataOutputStream(buffer), "bonjour".toByteArray())
        assertArrayEquals("bonjour".toByteArray(), TorLink.readFrame(DataInputStream(ByteArrayInputStream(buffer.toByteArray()))))
    }

    @Test fun `taille annoncee hors bornes refusee avant toute allocation`() {
        val lying = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(Int.MAX_VALUE) }.toByteArray()
        assertThrows(IOException::class.java) { TorLink.readFrame(DataInputStream(ByteArrayInputStream(lying))) }
        val zero = ByteArray(4)
        assertThrows(IOException::class.java) { TorLink.readFrame(DataInputStream(ByteArrayInputStream(zero))) }
    }

    @Test fun `HELLO lie au defi, au destinataire et a toute l'identite`() {
        val challenge = ByteArray(32) { 7 }
        val a = Hex.decode(alice.encryptionPublicKeyHex)
        val aSig = Hex.decode(alice.signingPublicKeyHex)
        val onion = "a".repeat(TorLink.ONION_SIZE).toByteArray()
        val b = Hex.decode(bob.encryptionPublicKeyHex)
        fun binding(c: ByteArray = challenge, o: ByteArray = onion, to: ByteArray = b) =
            TorLink.helloBinding(c, a, aSig, o, to)
        val sig = crypto.sign(binding(), alice.signingPrivateKeyHex)

        assertTrue(crypto.verify(binding(), sig, alice.signingPublicKeyHex))
        // rejoué sur un autre défi
        assertFalse(crypto.verify(binding(c = ByteArray(32) { 8 }), sig, alice.signingPublicKeyHex))
        // resservi par Bob à un tiers
        val carol = Hex.decode(crypto.generateIdentity().encryptionPublicKeyHex)
        assertFalse(crypto.verify(binding(to = carol), sig, alice.signingPublicKeyHex))
        // adresse onion remplacée en route
        assertFalse(crypto.verify(binding(o = "b".repeat(TorLink.ONION_SIZE).toByteArray()), sig, alice.signingPublicKeyHex))
        // Bob se faisant passer pour Alice
        val forged = crypto.sign(binding(), bob.signingPrivateKeyHex)
        assertFalse(crypto.verify(binding(), forged, alice.signingPublicKeyHex))
    }

    @Test fun `preuve X25519 - seul le detenteur de la cle annoncee la produit`() {
        val binding = TorLink.helloBinding(
            ByteArray(32) { 7 }, Hex.decode(alice.encryptionPublicKeyHex), Hex.decode(alice.signingPublicKeyHex),
            "a".repeat(TorLink.ONION_SIZE).toByteArray(), Hex.decode(bob.encryptionPublicKeyHex)
        )
        // Alice prouve sa clé ; Bob vérifie avec la sienne.
        val proof = crypto.helloKeyProof(alice.encryptionPrivateKeyHex, bob.encryptionPublicKeyHex, binding)
        assertEquals(TorLink.PROOF_SIZE, proof.size)
        assertTrue(crypto.verifyHelloKeyProof(bob.encryptionPrivateKeyHex, alice.encryptionPublicKeyHex, binding, proof))

        // Mallory annonce la clé X25519 d'Alice mais n'a que sa propre clé privée.
        val mallory = crypto.generateIdentity()
        val forged = crypto.helloKeyProof(mallory.encryptionPrivateKeyHex, bob.encryptionPublicKeyHex, binding)
        assertFalse(crypto.verifyHelloKeyProof(bob.encryptionPrivateKeyHex, alice.encryptionPublicKeyHex, binding, forged))

        // Rejouée sur un autre défi : le défi suit le préfixe "whisper-v3|tor-hello|".
        val prefix = "whisper-v3|tor-hello|".length
        val other = binding.copyOf().also { it[prefix] = (it[prefix] + 1).toByte() }
        assertFalse(crypto.verifyHelloKeyProof(bob.encryptionPrivateKeyHex, alice.encryptionPublicKeyHex, other, proof))

        // Clé « nulle » (point d'ordre faible) : secret prévisible, refusée.
        val zero = "00".repeat(32)
        assertFalse(crypto.verifyHelloKeyProof(bob.encryptionPrivateKeyHex, zero, binding, ByteArray(32)))
    }

    @Test fun `format du HELLO v3`() {
        assertEquals(3.toByte(), TorLink.VERSION)
        assertEquals(1 + 32 + 32 + TorLink.ONION_SIZE + 32 + 64, TorLink.HELLO_SIZE)
        assertEquals(TorLink.HELLO_SIZE - 64, TorLink.SIGNATURE_OFFSET)
    }
}
