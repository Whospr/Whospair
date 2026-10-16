package com.whisper.update

import android.content.Context
import com.whisper.security.PinCrypto
import com.whisper.services.StorageService
import org.bouncycastle.util.encoders.Hex
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * La mise à jour en cours de téléchargement, gardée sur le disque (1.3.6).
 *
 * Avant, elle n'existait qu'en mémoire : annuler la fenêtre d'installation
 * d'Android, ou laisser l'app se verrouiller, la perdait — et tout se
 * retéléchargeait par Tor. Elle survit maintenant à l'annulation, au
 * verrouillage, à une coupure (le téléchargement reprend où il en était), et
 * s'efface une fois installée.
 *
 * Chiffrée (« rien en clair sur le disque ») : AES-CTR, clé tirée au hasard et
 * gardée dans le stockage chiffré par le Keystore, détruite avec lui à
 * l'effacement. CTR permet d'écrire morceau par morceau à mesure qu'ils
 * arrivent, et de lire en flux vers l'installateur sans jamais tenir l'APK
 * entier en mémoire. L'intégrité vient du manifeste signé : l'empreinte
 * SHA-256 du clair est vérifiée avant toute installation.
 *
 * Fichier : [IV 16 octets][APK chiffré].
 *
 * Dans `:reach` (1.3.8, téléchargement poursuivi app fermée) : la clé est
 * confiée par l'app ([fixedKey]) et seuls [length] et [append] servent — ce
 * processus ne touche jamais au stockage chiffré, qui ne se partage pas
 * sans risque entre deux processus.
 */
class UpdateFile(context: Context, private val storage: StorageService?, private val fixedKey: ByteArray? = null) {

    private val file = File(context.noBackupFilesDir, NAME)

    /** Le manifeste du fichier gardé, signature revérifiée ; null s'il n'y en a pas. */
    fun manifest(): UpdateManifest? = UpdateManifest.verifyHex(
        storage?.getString(KEY_MANIFEST)?.ifEmpty { null },
        storage?.getString(KEY_MANIFEST_SIG)?.ifEmpty { null }
    )?.takeIf { file.exists() }

    /** Octets d'APK déjà reçus. */
    fun length(): Long = if (file.exists()) maxOf(0L, file.length() - IV_SIZE) else 0L

    fun isComplete(manifest: UpdateManifest): Boolean =
        manifest().let { it != null && it.sameAs(manifest) } && length() == manifest.size

    /**
     * Prépare le fichier pour [manifest] : le même qu'avant, on reprend où il
     * en était ; un autre, on repart de zéro.
     * @return octets déjà reçus.
     */
    @Synchronized
    fun begin(manifest: UpdateManifest): Long {
        val kept = manifest()
        if (kept != null && kept.sameAs(manifest) && length() <= manifest.size) return length()
        delete()
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(PinCrypto.randomBytes(IV_SIZE)) }
        storage?.saveString(KEY_MANIFEST, manifest.rawHex)
        storage?.saveString(KEY_MANIFEST_SIG, manifest.signatureHex)
        return 0L
    }

    /** Ajoute un morceau reçu, qui doit suivre exactement ce qui est déjà là. */
    @Synchronized
    fun append(offset: Long, data: ByteArray, start: Int, length: Int): Boolean = runCatching {
        check(offset == length()) { "Morceau hors séquence" }
        val sealed = cipher(Cipher.ENCRYPT_MODE, offset).doFinal(data, start, length)
        FileOutputStream(file, true).use { it.write(sealed) }
    }.isSuccess

    /** Empreinte SHA-256 du clair, lu en flux. */
    fun sha256(): ByteArray = MessageDigest.getInstance("SHA-256").also { copyTo(null, it) }.digest()

    /** Déchiffre en flux vers [out] (s'il y en a un) en nourrissant [digest]. */
    fun copyTo(out: OutputStream?, digest: MessageDigest) {
        val cipher = cipher(Cipher.DECRYPT_MODE, 0)
        file.inputStream().use { input ->
            input.skip(IV_SIZE.toLong())
            val chunk = ByteArray(CHUNK)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                val plain = cipher.update(chunk, 0, n) ?: continue
                digest.update(plain)
                out?.write(plain)
                plain.fill(0)
            }
        }
    }

    @Synchronized
    fun delete() {
        file.delete()
        storage?.remove(KEY_MANIFEST)
        storage?.remove(KEY_MANIFEST_SIG)
    }

    /** AES-CTR positionné sur [offset] : le compteur avance d'un bloc tous les 16 octets. */
    private fun cipher(mode: Int, offset: Long): Cipher {
        val iv = file.inputStream().use { input -> ByteArray(IV_SIZE).also { check(input.read(it) == IV_SIZE) } }
        val counter = BigInteger(1, iv).add(BigInteger.valueOf(offset / BLOCK))
            .mod(BigInteger.ONE.shiftLeft(128))
        val bytes = counter.toByteArray().takeLast(IV_SIZE).toByteArray()
        val start = ByteArray(IV_SIZE - bytes.size) + bytes
        return Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(mode, key(), IvParameterSpec(start))
            // Décalage dans le bloc : on consomme les octets de flux qui précèdent.
            val skip = (offset % BLOCK).toInt()
            if (skip > 0) update(ByteArray(skip))
        }
    }

    private fun key(): SecretKeySpec = SecretKeySpec(keyBytes(), "AES")

    /** La clé du fichier : confiée à `:reach` quand le téléchargement s'y poursuit. */
    fun keyBytes(): ByteArray {
        fixedKey?.let { return it }
        val store = checkNotNull(storage) { "Ni clé confiée ni stockage" }
        var hex = store.getString(KEY_SECRET)
        if (hex.length != 64) {
            hex = Hex.toHexString(PinCrypto.randomBytes(32))
            store.saveString(KEY_SECRET, hex)
        }
        return Hex.decode(hex)
    }

    companion object {
        private const val NAME = "update.part"
        private const val IV_SIZE = 16
        private const val BLOCK = 16
        private const val CHUNK = 256 * 1024
        private const val KEY_SECRET = "update_file_key"
        private const val KEY_MANIFEST = "update_file_manifest"
        private const val KEY_MANIFEST_SIG = "update_file_manifest_sig"

        /** Effacement d'urgence : la clé part avec le stockage, le fichier ici. */
        fun deleteFiles(context: Context) {
            File(context.noBackupFilesDir, NAME).delete()
        }
    }
}
