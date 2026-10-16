package com.whisper.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Tests unitaires de CryptoManager (JVM, sans device).
 *
 * Tout tourne hors Android : X25519, Ed25519, HKDF et AES-GCM viennent de
 * Bouncy Castle. Aucun accès au Keystore, donc aucun besoin d'émulateur.
 *
 * Les trois protections ajoutées d'après le prototype Replit ont chacune
 * leur test d'attaque : usurpation d'expéditeur, rejeu, altération du
 * contexte. Un test qui vérifie seulement que « ça marche » ne prouve rien —
 * c'est ce qui avait laissé passer un secret partagé aléatoire.
 */
class CryptoManagerTest {

    private lateinit var crypto: CryptoManager
    private lateinit var alice: WhisperKeyPair
    private lateinit var bob: WhisperKeyPair

    @Before
    fun setUp() {
        crypto = CryptoManager()
        alice = crypto.generateIdentity()
        bob = crypto.generateIdentity()
    }

    private fun encryptAliceToBob(
        text: String = "Rendez-vous à 21h",
        counter: Long = 1
    ): EncryptedData = crypto.encryptFor(
        plaintext = text.toByteArray(Charsets.UTF_8),
        theirPublicKeyHex = bob.encryptionPublicKeyHex,
        ourPublicKeyHex = alice.encryptionPublicKeyHex,
        ourSigningPrivateKeyHex = alice.signingPrivateKeyHex,
        counter = counter
    )

    private fun decryptAtBob(
        data: EncryptedData,
        senderSigningKey: String = alice.signingPublicKeyHex,
        senderKey: String = alice.encryptionPublicKeyHex,
        lastSeen: Long = -1
    ): ByteArray = crypto.decryptFrom(
        encrypted = data,
        ourPrivateKeyHex = bob.encryptionPrivateKeyHex,
        ourPublicKeyHex = bob.encryptionPublicKeyHex,
        theirPublicKeyHex = senderKey,
        theirSigningPublicKeyHex = senderSigningKey,
        lastSeenCounter = lastSeen
    )

    // ==================== IDENTITÉ ====================

    @Test
    fun `une identite porte quatre cles de 64 hex`() {
        listOf(
            alice.encryptionPublicKeyHex, alice.encryptionPrivateKeyHex,
            alice.signingPublicKeyHex, alice.signingPrivateKeyHex
        ).forEach { assertEquals(64, it.length) }
    }

    @Test
    fun `les cles publiques derivent bien des privees`() {
        assertEquals(
            alice.encryptionPublicKeyHex,
            crypto.derivePublicKey(alice.encryptionPrivateKeyHex)
        )
        assertEquals(
            alice.signingPublicKeyHex,
            crypto.deriveSigningPublicKey(alice.signingPrivateKeyHex)
        )
    }

    @Test
    fun `chiffrement et signature sont deux paires distinctes`() {
        assertNotEquals(alice.encryptionPublicKeyHex, alice.signingPublicKeyHex)
    }

    @Test
    fun `deux identites successives different`() {
        assertNotEquals(alice.encryptionPublicKeyHex, bob.encryptionPublicKeyHex)
    }

    // ==================== ALLER-RETOUR ====================

    @Test
    fun `alice chiffre pour bob, bob dechiffre`() {
        val message = "Rendez-vous à 21h — accents inclus: éàçûï"
        val decrypted = decryptAtBob(encryptAliceToBob(message))

        assertEquals(message, String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun `chaque message embarque cle ephemere et signature`() {
        val encrypted = encryptAliceToBob()
        assertEquals(EncryptedData.EPHEMERAL_KEY_SIZE, encrypted.ephemeralPublicKey.size)
        assertEquals(EncryptedData.SIGNATURE_SIZE, encrypted.signature.size)
        assertEquals(EncryptedData.NONCE_SIZE, encrypted.nonce.size)
        assertEquals(EncryptedData.TAG_SIZE, encrypted.tag.size)
    }

    @Test
    fun `message vide et message long`() {
        val empty = crypto.encryptFor(
            ByteArray(0), bob.encryptionPublicKeyHex,
            alice.encryptionPublicKeyHex, alice.signingPrivateKeyHex, 1
        )
        assertEquals(0, decryptAtBob(empty).size)

        val big = ByteArray(200_000) { (it % 251).toByte() }
        val encryptedBig = crypto.encryptFor(
            big.copyOf(), bob.encryptionPublicKeyHex,
            alice.encryptionPublicKeyHex, alice.signingPrivateKeyHex, 2
        )
        assertArrayEquals(big, decryptAtBob(encryptedBig))
    }

    // ==================== ATTAQUE 1 : USURPATION ====================

    @Test
    fun `un tiers ne peut pas se faire passer pour alice`() {
        // Eve connaît la clé publique de Bob — c'est public — et lui chiffre
        // un message en prétendant venir d'Alice. Sans authentification,
        // l'ancienne version acceptait ça sans broncher.
        val eve = crypto.generateIdentity()

        val forged = crypto.encryptFor(
            plaintext = "Vire l'argent sur ce compte".toByteArray(),
            theirPublicKeyHex = bob.encryptionPublicKeyHex,
            ourPublicKeyHex = alice.encryptionPublicKeyHex,  // Eve MENT sur l'expéditeur
            ourSigningPrivateKeyHex = eve.signingPrivateKeyHex,
            counter = 1
        )

        try {
            decryptAtBob(forged)
            fail("Un message signé par Eve mais attribué à Alice doit être rejeté")
        } catch (e: SignatureVerificationException) {
            assertTrue(e.message!!.contains("Signature"))
        }
    }

    @Test
    fun `alterer la signature fait echouer la verification`() {
        val encrypted = encryptAliceToBob()
        val corrupted = encrypted.copy(
            signature = encrypted.signature.copyOf().also { it[0] = (it[0] + 1).toByte() }
        )

        try {
            decryptAtBob(corrupted)
            fail("Signature altérée acceptée")
        } catch (e: SignatureVerificationException) {
            // attendu
        }
    }

    @Test
    fun `un tiers ne peut pas dechiffrer`() {
        val eve = crypto.generateIdentity()
        val encrypted = encryptAliceToBob("secret")

        try {
            crypto.decryptFrom(
                encrypted = encrypted,
                ourPrivateKeyHex = eve.encryptionPrivateKeyHex,
                ourPublicKeyHex = eve.encryptionPublicKeyHex,
                theirPublicKeyHex = alice.encryptionPublicKeyHex,
                theirSigningPublicKeyHex = alice.signingPublicKeyHex
            )
            fail("Eve n'aurait pas dû pouvoir déchiffrer")
        } catch (e: SecurityException) {
            // attendu : la signature ne couvre pas Eve comme destinataire
        }
    }

    // ==================== ATTAQUE 2 : REJEU ====================

    @Test
    fun `un message deja recu est rejete`() {
        val encrypted = encryptAliceToBob(counter = 5)

        // Première réception : acceptée.
        assertEquals("Rendez-vous à 21h", String(decryptAtBob(encrypted, lastSeen = 4)))

        // Rejouée alors que le compteur 5 est déjà connu : refusée.
        try {
            decryptAtBob(encrypted, lastSeen = 5)
            fail("Un message au compteur déjà vu doit être rejeté")
        } catch (e: SignatureVerificationException) {
            assertTrue(e.message!!.contains("Rejeu"))
        }
    }

    @Test
    fun `un compteur anterieur est rejete`() {
        val old = encryptAliceToBob(counter = 2)
        try {
            decryptAtBob(old, lastSeen = 10)
            fail("Un compteur inférieur au dernier vu doit être rejeté")
        } catch (e: SignatureVerificationException) {
            // attendu
        }
    }

    @Test
    fun `les compteurs croissants passent`() {
        var lastSeen = -1L
        (1L..5L).forEach { c ->
            val text = String(decryptAtBob(encryptAliceToBob("msg $c", c), lastSeen = lastSeen))
            assertEquals("msg $c", text)
            lastSeen = c
        }
    }

    // ==================== ATTAQUE 3 : ALTÉRATION DU CONTEXTE ====================

    @Test
    fun `modifier le compteur casse le dechiffrement`() {
        // Le compteur est dans les données authentifiées du GCM : le changer
        // invalide le tag, même si la signature était ignorée.
        val encrypted = encryptAliceToBob(counter = 3)
        val tampered = encrypted.copy(counter = 4)

        try {
            decryptAtBob(tampered, lastSeen = 3)
            fail("Compteur modifié accepté")
        } catch (e: SecurityException) {
            // attendu
        }
    }

    @Test
    fun `modifier l horodatage casse le dechiffrement`() {
        val encrypted = encryptAliceToBob()
        val tampered = encrypted.copy(timestamp = encrypted.timestamp + 1000)

        try {
            decryptAtBob(tampered)
            fail("Horodatage modifié accepté")
        } catch (e: SecurityException) {
            // attendu
        }
    }

    @Test
    fun `alterer le ciphertext casse le dechiffrement`() {
        val encrypted = encryptAliceToBob("intact")
        val corrupted = encrypted.copy(
            ciphertext = encrypted.ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() }
        )

        try {
            decryptAtBob(corrupted)
            fail("Ciphertext altéré accepté")
        } catch (e: SecurityException) {
            // attendu
        }
    }

    @Test
    fun `alterer la cle ephemere casse le dechiffrement`() {
        val encrypted = encryptAliceToBob()
        val corrupted = encrypted.copy(
            ephemeralPublicKey = encrypted.ephemeralPublicKey.copyOf()
                .also { it[0] = (it[0] + 1).toByte() }
        )

        try {
            decryptAtBob(corrupted)
            fail("Clé éphémère altérée acceptée")
        } catch (e: SecurityException) {
            // attendu
        }
    }

    @Test
    fun `deux chiffrements du meme texte different`() {
        val a = encryptAliceToBob("identique", 1)
        val b = encryptAliceToBob("identique", 2)

        assertFalse(a.ephemeralPublicKey.contentEquals(b.ephemeralPublicKey))
        assertFalse(a.nonce.contentEquals(b.nonce))
        assertFalse(a.ciphertext.contentEquals(b.ciphertext))
    }

    // ==================== SIGNATURE BRUTE ====================

    @Test
    fun `signer puis verifier`() {
        val data = "charge utile".toByteArray()
        val sig = crypto.sign(data, alice.signingPrivateKeyHex)

        assertTrue(crypto.verify(data, sig, alice.signingPublicKeyHex))
        assertFalse(crypto.verify(data, sig, bob.signingPublicKeyHex))
        assertFalse(crypto.verify("autre".toByteArray(), sig, alice.signingPublicKeyHex))
    }

    @Test
    fun `une signature malformee ne fait pas planter`() {
        assertFalse(crypto.verify("x".toByteArray(), ByteArray(3), alice.signingPublicKeyHex))
    }

    // ==================== SAFETY NUMBER ====================

    @Test
    fun `les deux parties obtiennent le meme safety number`() {
        // Le point de toute la fonctionnalité : Alice et Bob doivent pouvoir
        // comparer le MÊME nombre à voix haute. Une empreinte d'une seule clé
        // leur en donnerait deux différents, donc rien à comparer.
        val fromAlice = crypto.pairSafetyNumber(
            alice.encryptionPublicKeyHex, alice.signingPublicKeyHex,
            bob.encryptionPublicKeyHex, bob.signingPublicKeyHex
        )
        val fromBob = crypto.pairSafetyNumber(
            bob.encryptionPublicKeyHex, bob.signingPublicKeyHex,
            alice.encryptionPublicKeyHex, alice.signingPublicKeyHex
        )

        assertEquals(fromAlice, fromBob)
    }

    @Test
    fun `le safety number change si une cle change`() {
        val eve = crypto.generateIdentity()

        val legitimate = crypto.pairSafetyNumber(
            alice.encryptionPublicKeyHex, alice.signingPublicKeyHex,
            bob.encryptionPublicKeyHex, bob.signingPublicKeyHex
        )
        // Un homme du milieu substitue sa clé à celle de Bob.
        val intercepted = crypto.pairSafetyNumber(
            alice.encryptionPublicKeyHex, alice.signingPublicKeyHex,
            eve.encryptionPublicKeyHex, eve.signingPublicKeyHex
        )

        assertNotEquals(legitimate, intercepted)
    }

    @Test
    fun `substituer la seule cle de signature change le safety number`() {
        val eve = crypto.generateIdentity()

        val legitimate = crypto.pairSafetyNumber(
            alice.encryptionPublicKeyHex, alice.signingPublicKeyHex,
            bob.encryptionPublicKeyHex, bob.signingPublicKeyHex
        )
        val partial = crypto.pairSafetyNumber(
            alice.encryptionPublicKeyHex, alice.signingPublicKeyHex,
            bob.encryptionPublicKeyHex, eve.signingPublicKeyHex
        )

        assertNotEquals(legitimate, partial)
    }

    @Test
    fun `le safety number est lisible a voix haute`() {
        val number = crypto.pairSafetyNumber(
            alice.encryptionPublicKeyHex, alice.signingPublicKeyHex,
            bob.encryptionPublicKeyHex, bob.signingPublicKeyHex
        )
        val groups = number.split(" ")

        assertEquals(8, groups.size)
        assertTrue(groups.all { it.length == 5 && it.all(Char::isDigit) })
    }

    @Test
    fun `le safety number est stable`() {
        val a = crypto.pairSafetyNumber(
            alice.encryptionPublicKeyHex, alice.signingPublicKeyHex,
            bob.encryptionPublicKeyHex, bob.signingPublicKeyHex
        )
        val b = crypto.pairSafetyNumber(
            alice.encryptionPublicKeyHex, alice.signingPublicKeyHex,
            bob.encryptionPublicKeyHex, bob.signingPublicKeyHex
        )
        assertEquals(a, b)
    }

    // ==================== UTILITAIRES ====================

    @Test
    fun `zeroize efface le tableau`() {
        val data = ByteArray(32) { 0x42 }
        crypto.zeroize(data)
        assertTrue(data.all { it == 0.toByte() })
    }

    // ==================== ARRIVÉE / AFFICHAGE ====================

    @Test
    fun `arrivee verifiee une fois, affichage relisible a volonte`() {
        val data = encryptAliceToBob(counter = 5)
        // Arrivée : origine + compteur, sans clair.
        crypto.verifyOrigin(data, bob.encryptionPublicKeyHex, alice.encryptionPublicKeyHex, alice.signingPublicKeyHex, lastSeenCounter = 4)
        // Le compteur a avancé à 5 : le même message ne repasse pas l'arrivée…
        try {
            crypto.verifyOrigin(data, bob.encryptionPublicKeyHex, alice.encryptionPublicKeyHex, alice.signingPublicKeyHex, lastSeenCounter = 5)
            fail("Rejeu accepté à l'arrivée")
        } catch (e: SignatureVerificationException) { }
        // …mais l'affichage, lui, le relit autant de fois qu'il faut.
        repeat(3) { assertEquals("Rendez-vous à 21h", String(decryptAtBob(data), Charsets.UTF_8)) }
    }

    @Test
    fun `verifyOrigin refuse une signature d'un autre`() {
        val data = encryptAliceToBob()
        try {
            crypto.verifyOrigin(data, bob.encryptionPublicKeyHex, alice.encryptionPublicKeyHex, bob.signingPublicKeyHex)
            fail("Usurpation acceptée")
        } catch (e: SignatureVerificationException) { }
    }
}
