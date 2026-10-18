package com.whisper.security

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * La partie pure du coffre : dériver une clé d'un PIN, emballer un secret.
 * Aucune dépendance Android — c'est ce qui la rend testable sur la JVM.
 *
 * ### Pourquoi Argon2id, et pourquoi ça ne suffit pas seul
 *
 * Argon2id est lent et gourmand en mémoire exprès : chaque essai de PIN coûte
 * au moins une fraction de seconde et 19 Mo de RAM, ce qui rend les cartes
 * graphiques peu utiles à un attaquant. Mais un PIN à 6 chiffres, c'est un
 * million de possibilités — même à une demi-seconde l'essai, quelques jours
 * sur une machine. Argon2 seul ne tient donc pas face à quelqu'un qui aurait
 * copié le blob.
 *
 * Le blob n'est jamais copiable en clair : il vit dans
 * EncryptedSharedPreferences, sous une clé du Keystore Android, non
 * exportable (dans le TEE quand l'appareil en a un). Hors de l'appareil, il
 * est illisible. Sur l'appareil, il faut passer par l'app — qui compte les
 * essais et efface tout au dixième.
 * C'est la combinaison des deux qui protège, pas l'un ou l'autre.
 *
 * Limite honnête : un appareil rooté peut appeler le Keystore sans passer
 * par l'app, puis attaquer la couche Argon2 hors ligne. Un code plus long
 * que 6 chiffres est alors la seule défense qui compte.
 */
object PinCrypto {

    /** Premier octet du blob : permet de changer les paramètres un jour sans casser l'existant. */
    private const val VERSION: Byte = 1

    // Profil OWASP minimal pour Argon2id : supportable par un téléphone modeste.
    private const val MEMORY_KIB = 19 * 1024
    private const val ITERATIONS = 2
    private const val PARALLELISM = 1

    const val SALT_SIZE = 16
    const val KEY_SIZE = 32
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    /** Lie le blob à son usage : un blob d'un autre contexte ne se déchiffre pas ici. */
    private val AAD = "whisper-vault-v1".toByteArray(Charsets.UTF_8)

    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun deriveKey(pin: CharArray, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(MEMORY_KIB)
            .withIterations(ITERATIONS)
            .withParallelism(PARALLELISM)
            .build()
        return ByteArray(KEY_SIZE).also { out ->
            Argon2BytesGenerator().apply { init(params) }.generateBytes(pin, out)
        }
    }

    /** [version][iv][chiffré + tag]. */
    fun wrap(secret: ByteArray, kek: ByteArray): ByteArray {
        val iv = randomBytes(IV_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(AAD)
        }
        return byteArrayOf(VERSION) + iv + cipher.doFinal(secret)
    }

    /** null = mauvais PIN (le tag GCM ne passe pas) ou blob illisible. */
    fun unwrap(blob: ByteArray, kek: ByteArray): ByteArray? {
        if (blob.size <= 1 + IV_SIZE || blob[0] != VERSION) return null
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"),
                    GCMParameterSpec(TAG_BITS, blob, 1, IV_SIZE))
                updateAAD(AAD)
            }.doFinal(blob, 1 + IV_SIZE, blob.size - 1 - IV_SIZE)
        } catch (e: AEADBadTagException) {
            null
        }
    }

    /**
     * Sous-clé tirée d'une clé maîtresse par HKDF-SHA256. La clé de base
     * ouvre SQLCipher ; sa dérivée « identity » scelle l'identité (clés et
     * graine onion) et sert de racine aux sous-clés « local-copy » et
     * « inbox ».
     */
    fun subKey(master: ByteArray, label: String): ByteArray = ByteArray(KEY_SIZE).also { out ->
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(master, null, "whisper-v1|$label".toByteArray(Charsets.UTF_8)))
        }.generateBytes(out, 0, KEY_SIZE)
    }

    /** Comparaison en temps constant : le temps de réponse ne dit rien du contenu. */
    fun same(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
