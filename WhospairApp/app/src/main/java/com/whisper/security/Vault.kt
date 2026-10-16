package com.whisper.security

import android.content.Context
import com.whisper.database.WhisperDatabase
import com.whisper.reach.Inbox
import com.whisper.reach.Reachability
import com.whisper.services.IdentityService
import com.whisper.services.StorageService
import com.whisper.tor.TorRuntime
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.util.encoders.Hex
import javax.inject.Inject
import javax.inject.Singleton

class VaultLockedException : IllegalStateException("Coffre verrouillé")

sealed interface UnlockResult {
    data object Success : UnlockResult
    data class WrongPin(val attemptsLeft: Int) : UnlockResult

    /**
     * Tout est effacé : dixième échec, ou PIN de contrainte. Les deux cas
     * produisent EXACTEMENT le même résultat, visible comme invisible :
     * rien ne doit permettre de savoir lequel des deux s'est produit.
     */
    data object Wiped : UnlockResult
}

/**
 * Le coffre : détient la clé de la base, et seulement après un PIN correct.
 *
 * Chaîne complète :
 *   PIN ──Argon2id──▶ clé d'emballage ──AES-GCM──▶ clé de base (32 octets)
 *   le blob emballé vit dans EncryptedSharedPreferences (clé du Keystore)
 *   la clé de base ouvre SQLCipher, qui chiffre le fichier entier ;
 *   HKDF(clé de base, "identity") scelle les clés privées d'identité.
 *
 * La clé de base n'est jamais gardée en mémoire : elle est remise à SQLCipher
 * puis effacée. Verrouiller, c'est tuer le processus (voir SessionLifecycle).
 */
@Singleton
class Vault @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: StorageService,
    private val identity: IdentityService
) {
    @Volatile
    private var db: WhisperDatabase? = null

    /**
     * Une vérification de PIN à la fois (1.3.7) : deux vérifications
     * simultanées lisaient le même compteur d'échecs et écrivaient la même
     * valeur — en tapant vite, le compteur n'avançait plus, et l'effacement
     * au dixième échec comme le freinage étaient contournés.
     */
    private val attempts = Mutex()


    val database: WhisperDatabase get() = db ?: throw VaultLockedException()

    fun hasPin(): Boolean = storage.contains(KEY_WRAPPED)

    /**
     * Longueur du PIN, retenue à sa création : le clavier valide seul dès
     * qu'elle est atteinte. 0 = inconnue (installation d'avant), bouton ✓.
     *
     * Elle ne dit pas le PIN, seulement combien de chiffres chercher ; les
     * dix essais avant effacement restent le vrai rempart.
     */
    fun pinLength(): Int = storage.getInt(KEY_PIN_LENGTH)

    /**
     * Premier réglage du PIN — ou migration d'une installation antérieure,
     * dont la clé de base était stockée sans PIN : elle est reprise telle
     * quelle et emballée, la base existante reste lisible.
     */
    suspend fun setup(pin: CharArray): Unit = withContext(Dispatchers.Default) {
        require(isAcceptable(pin)) { "PIN trop court" }

        val legacy = storage.getString(KEY_LEGACY_PASSPHRASE)
        val baseKey = if (legacy.isNotBlank()) {
            Hex.decode(legacy)
        } else {
            // Aucune clé du tout : un fichier présent ne peut être qu'une base
            // d'avant le chiffrement, que SQLCipher refuserait d'ouvrir.
            WhisperDatabase.deleteLegacyPlaintextDatabase(context)
            PinCrypto.randomBytes(PinCrypto.KEY_SIZE)
        }

        val salt = PinCrypto.randomBytes(PinCrypto.SALT_SIZE)
        val kek = PinCrypto.deriveKey(pin, salt)
        storage.saveString(KEY_SALT, Hex.toHexString(salt))
        storage.saveString(KEY_WRAPPED, Hex.toHexString(PinCrypto.wrap(baseKey, kek)))
        storage.saveInt(KEY_FAILED, 0)
        storage.saveInt(KEY_PIN_LENGTH, pin.size)
        // Après l'écriture du blob, jamais avant : un crash entre les deux ne
        // doit pas laisser une base sans aucune clé pour l'ouvrir.
        storage.remove(KEY_LEGACY_PASSPHRASE)

        kek.fill(0)
        open(baseKey)
    }

    /**
     * Vérifie le PIN sans rouvrir la base, avec EXACTEMENT les mêmes règles que
     * le déverrouillage : essais comptés, 10 échecs et PIN de contrainte
     * effacent tout. Pour « Désactiver le masquage avec le code PIN » (1.3.7).
     */
    suspend fun verify(pin: CharArray): UnlockResult = unlock(pin, openOnSuccess = false)

    suspend fun unlock(pin: CharArray, openOnSuccess: Boolean = true): UnlockResult = attempts.withLock { checkPin(pin, openOnSuccess) }

    private suspend fun checkPin(pin: CharArray, openOnSuccess: Boolean): UnlockResult = withContext(Dispatchers.Default) {
        val failed = storage.getInt(KEY_FAILED)

        // Frein à partir du 4e échec. Le vrai rempart est l'effacement au
        // dixième ; ce délai sert surtout à décourager l'essai à la chaîne.
        if (failed >= 3) delay(minOf(1_000L shl (failed - 3), 30_000L))

        // Compté AVANT la vérification : tuer l'app pendant le calcul ne doit
        // pas permettre de réessayer gratuitement.
        storage.saveInt(KEY_FAILED, failed + 1)

        val salt = Hex.decode(storage.getString(KEY_SALT))
        val blob = Hex.decode(storage.getString(KEY_WRAPPED))
        val kek = PinCrypto.deriveKey(pin, salt)
        val baseKey = PinCrypto.unwrap(blob, kek)
        kek.fill(0)

        // Le PIN de contrainte est testé À CHAQUE FOIS, même quand le vrai PIN
        // vient de passer : deux calculs Argon2 dans tous les cas, donc un
        // temps de réponse identique quel que soit le PIN saisi.
        val duress = isDuress(pin)

        when {
            duress -> {
                baseKey?.fill(0)
                wipe()
                UnlockResult.Wiped
            }
            baseKey != null -> {
                storage.saveInt(KEY_FAILED, 0)
                if (pinLength() == 0) storage.saveInt(KEY_PIN_LENGTH, pin.size)
                if (openOnSuccess) open(baseKey) else baseKey.fill(0)
                UnlockResult.Success
            }
            failed + 1 >= MAX_ATTEMPTS -> {
                wipe()
                UnlockResult.Wiped
            }
            else -> UnlockResult.WrongPin(MAX_ATTEMPTS - failed - 1)
        }
    }

    /**
     * Règle le PIN de contrainte. Le PIN actuel est exigé : sans lui,
     * n'importe qui ayant l'app ouverte pourrait piéger le déverrouillage.
     */
    suspend fun setDuressPin(currentPin: CharArray, duressPin: CharArray): Boolean =
        withContext(Dispatchers.Default) {
            if (!isAcceptable(duressPin) || currentPin.contentEquals(duressPin)) return@withContext false
            // Même longueur que le vrai PIN : deux longueurs retenues
            // trahiraient qu'un second PIN existe.
            if (pinLength() != 0 && duressPin.size != pinLength()) return@withContext false

            val kek = PinCrypto.deriveKey(currentPin, Hex.decode(storage.getString(KEY_SALT)))
            val ok = PinCrypto.unwrap(Hex.decode(storage.getString(KEY_WRAPPED)), kek)
            kek.fill(0)
            if (ok == null) return@withContext false
            ok.fill(0)

            val salt = PinCrypto.randomBytes(PinCrypto.SALT_SIZE)
            storage.saveString(KEY_DURESS_SALT, Hex.toHexString(salt))
            storage.saveString(KEY_DURESS_CHECK, Hex.toHexString(PinCrypto.deriveKey(duressPin, salt)))
            true
        }

    enum class ChangeResult { CHANGED, CHANGED_DURESS_REMOVED, WRONG_CURRENT, REJECTED }

    /**
     * Change le PIN : la clé de base est réemballée, la base ne bouge pas.
     *
     * Un nouveau PIN de longueur différente retire le PIN de contrainte :
     * sinon la validation automatique ne pourrait jamais l'atteindre.
     * Un nouveau PIN égal au PIN de contrainte est refusé.
     */
    suspend fun changePin(currentPin: CharArray, newPin: CharArray): ChangeResult =
        withContext(Dispatchers.Default) {
            if (!isAcceptable(newPin)) return@withContext ChangeResult.REJECTED

            val kek = PinCrypto.deriveKey(currentPin, Hex.decode(storage.getString(KEY_SALT)))
            val baseKey = PinCrypto.unwrap(Hex.decode(storage.getString(KEY_WRAPPED)), kek)
            kek.fill(0)
            if (baseKey == null) return@withContext ChangeResult.WRONG_CURRENT
            if (isDuress(newPin)) {
                baseKey.fill(0)
                return@withContext ChangeResult.REJECTED
            }

            val salt = PinCrypto.randomBytes(PinCrypto.SALT_SIZE)
            val newKek = PinCrypto.deriveKey(newPin, salt)
            storage.saveString(KEY_SALT, Hex.toHexString(salt))
            storage.saveString(KEY_WRAPPED, Hex.toHexString(PinCrypto.wrap(baseKey, newKek)))
            newKek.fill(0)
            baseKey.fill(0)

            val lengthChanged = pinLength() != newPin.size
            storage.saveInt(KEY_PIN_LENGTH, newPin.size)
            if (lengthChanged && storage.contains(KEY_DURESS_CHECK)) {
                storage.remove(KEY_DURESS_SALT)
                storage.remove(KEY_DURESS_CHECK)
                ChangeResult.CHANGED_DURESS_REMOVED
            } else {
                ChangeResult.CHANGED
            }
        }

    /**
     * Efface tout : clé de base, identité, base, réglages, clé du Keystore.
     *
     * Détruire le blob suffirait à rendre la base illisible. On supprime aussi
     * le fichier et l'identité, pour que l'app ressemble à une installation
     * neuve — y compris aux yeux de celui qui a forcé la saisie.
     *
     * La base part AVANT le stockage : si quelque chose casse en route, mieux
     * vaut une clé sans base qu'une base avec sa clé.
     */
    fun wipe() {
        // L'arrière-plan d'abord : il tient l'adresse en ligne et range dans la boîte.
        runCatching { Reachability.killBackground(context) }
        Inbox.deleteAll(context)
        db?.close()
        db = null
        context.deleteDatabase(WhisperDatabase.DATABASE_NAME)
        TorRuntime.deleteFiles(context)
        com.whisper.update.UpdateFile.deleteFiles(context)
        storage.destroy()
    }

    private fun isDuress(pin: CharArray): Boolean {
        val salt = storage.getString(KEY_DURESS_SALT)
        val check = storage.getString(KEY_DURESS_CHECK)
        if (salt.isBlank() || check.isBlank()) {
            // Pas de PIN de contrainte : on brûle quand même un calcul Argon2,
            // sinon le temps de déverrouillage trahirait son absence.
            PinCrypto.deriveKey(pin, ByteArray(PinCrypto.SALT_SIZE)).fill(0)
            return false
        }
        val candidate = PinCrypto.deriveKey(pin, Hex.decode(salt))
        return PinCrypto.same(candidate, Hex.decode(check)).also { candidate.fill(0) }
    }

    /**
     * SQLCipher remet sa copie à zéro ; on efface la nôtre.
     *
     * Room ouvre la base paresseusement, au premier accès d'un DAO : une
     * erreur d'ouverture éclaterait alors plus tard, n'importe où. On force
     * l'ouverture ici pour qu'elle remonte au déverrouillage, où LockGate
     * l'affiche.
     */
    private fun open(baseKey: ByteArray) {
        try {
            identity.unlock(PinCrypto.subKey(baseKey, "identity"))
            db = WhisperDatabase.build(context, baseKey.copyOf()).also {
                it.openHelper.writableDatabase
            }
        } finally {
            baseKey.fill(0)
        }
    }

    companion object {
        const val MIN_PIN_LENGTH = 6
        const val MAX_ATTEMPTS = 10

        fun isAcceptable(pin: CharArray) = pin.size >= MIN_PIN_LENGTH && pin.all { it.isDigit() }

        private const val KEY_SALT = "vault_salt"
        private const val KEY_WRAPPED = "vault_wrapped_key"
        private const val KEY_FAILED = "vault_failed_attempts"
        private const val KEY_DURESS_SALT = "vault_duress_salt"
        private const val KEY_DURESS_CHECK = "vault_duress_check"
        private const val KEY_PIN_LENGTH = "vault_pin_length"

        /** Clé de base stockée sans PIN, avant la phase 7. Migrée au premier réglage. */
        private const val KEY_LEGACY_PASSPHRASE = StorageService.KEY_DB_PASSPHRASE
    }
}
