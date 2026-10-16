package com.whisper.services

import com.whisper.crypto.CryptoManager
import com.whisper.models.Identity
import com.whisper.reach.InboxSeal
import com.whisper.security.PinCrypto
import com.whisper.tor.OnionAddress
import org.bouncycastle.util.encoders.Hex

/**
 * Service de gestion de l'identité utilisateur unique.
 *
 * ### Les clés privées sont sous le PIN
 *
 * Les clés publiques restent lisibles sans PIN (elles ne sont pas secrètes,
 * et l'onboarding en a besoin pour savoir qu'une identité existe). Les deux
 * clés privées, elles, sont scellées ensemble en AES-GCM avec une sous-clé de
 * la clé du coffre : sans le PIN, elles n'existent que chiffrées.
 *
 * Le seul moment où elles sont en clair sur le disque : entre la création de
 * l'identité (onboarding) et le choix du PIN, qui vient juste après. Le
 * premier déverrouillage les scelle et efface la version en clair.
 */
class IdentityService(
    private val storageService: StorageService,
    private val cryptoManager: CryptoManager = CryptoManager()
) {

    /** Sous-clé de scellement, présente seulement coffre ouvert. */
    @Volatile private var sealKey: ByteArray? = null

    /** Clés privées déchiffrées au déverrouillage — X25519, Ed25519 (hex) et graine onion. */
    private class Secrets(val encryptionHex: String, val signingHex: String, val onionSeed: ByteArray) {
        val onionAddress: String = OnionAddress.fromSeed(onionSeed)
    }

    @Volatile private var secrets: Secrets? = null

    /**
     * Appelé par le coffre à l'ouverture. Scelle une identité encore en clair,
     * complète un sceau d'avant l'onion, puis garde les clés privées en
     * mémoire — que le processus tué en arrière-plan emporte avec lui.
     *
     * Sceau : X25519[32] ‖ Ed25519[32] ‖ graine onion[32].
     *
     * @throws IllegalStateException si le sceau ne s'ouvre pas : identité
     *   corrompue. On ne régénère RIEN en silence — les contacts perdraient
     *   la trace de qui on est.
     */
    fun unlock(vaultSubKey: ByteArray) {
        sealKey = vaultSubKey
        localKey = PinCrypto.subKey(vaultSubKey, "local-copy")
        inboxKey = InboxSeal.privateKey(vaultSubKey)
        val sealed = storageService.getString(StorageService.KEY_IDENTITY_SEALED).takeIf { it.isNotBlank() }
            ?.let {
                PinCrypto.unwrap(Hex.decode(it), vaultSubKey)
                    ?: throw IllegalStateException("Identité scellée illisible")
            }
        // Graine onion déjà scellée : on la garde, l'adresse ne doit jamais
        // changer (un crash entre sceau et effacement du clair repasse ici).
        val onionSeed = sealed?.takeIf { it.size == SEALED_SIZE }?.copyOfRange(64, 96)
            ?: PinCrypto.randomBytes(32)

        val plainX = storageService.getString(StorageService.KEY_IDENTITY_PRIVKEY)
        val plainEd = storageService.getString(StorageService.KEY_IDENTITY_SIGN_PRIV)
        when {
            plainX.isNotBlank() && plainEd.isNotBlank() -> seal(plainX, plainEd, onionSeed)
            sealed != null && sealed.size == SEALED_SIZE ->
                secrets = Secrets(Hex.toHexString(sealed, 0, 32), Hex.toHexString(sealed, 32, 32), onionSeed)
            // Sceau d'avant l'onion (64 octets) : on le complète.
            sealed != null -> seal(Hex.toHexString(sealed, 0, 32), Hex.toHexString(sealed, 32, 32), onionSeed)
        }
        sealed?.fill(0)
    }

    /**
     * Le sceau est écrit AVANT d'effacer la version en clair : un crash entre
     * les deux laisse les deux copies, et le prochain déverrouillage rescelle.
     */
    private fun seal(encryptionPrivHex: String, signingPrivHex: String, onionSeed: ByteArray) {
        val key = sealKey ?: return
        val raw = Hex.decode(encryptionPrivHex) + Hex.decode(signingPrivHex) + onionSeed
        storageService.saveString(StorageService.KEY_IDENTITY_SEALED, Hex.toHexString(PinCrypto.wrap(raw, key)))
        raw.fill(0)
        storageService.remove(StorageService.KEY_IDENTITY_PRIVKEY)
        storageService.remove(StorageService.KEY_IDENTITY_SIGN_PRIV)
        secrets = Secrets(encryptionPrivHex, signingPrivHex, onionSeed)
    }

    /**
     * Clé de NOS copies de messages envoyés, en mémoire coffre ouvert.
     *
     * L'expéditeur ne peut pas relire un message ECIES (la clé éphémère est
     * détruite à l'envoi) : on garde donc, à part, une copie chiffrée pour
     * soi. Elle ne quitte jamais le téléphone et meurt avec le message.
     */
    @Volatile private var localKey: ByteArray? = null

    fun sealLocal(plain: String): ByteArray? =
        localKey?.let { PinCrypto.wrap(plain.toByteArray(Charsets.UTF_8), it) }

    /** Octets bruts (photo de profil) : même clé, même format que sealLocal. */
    fun sealLocalBytes(plain: ByteArray): ByteArray? =
        localKey?.let { PinCrypto.wrap(plain, it) }

    fun openLocalBytes(blob: ByteArray): ByteArray? =
        localKey?.let { PinCrypto.unwrap(blob, it) }

    /**
     * Boîte de réception d'arrière-plan (phase 13). Le processus d'arrière-
     * plan n'en reçoit que la clé publique : il range, il ne relit pas.
     */
    @Volatile private var inboxKey: ByteArray? = null

    fun inboxPublicKey(): ByteArray? = inboxKey?.let(InboxSeal::publicKey)

    fun openInbox(blob: ByteArray): ByteArray? = inboxKey?.let { InboxSeal.open(blob, it) }

    fun openLocal(blob: ByteArray): String? =
        localKey?.let { key -> PinCrypto.unwrap(blob, key)?.let { String(it, Charsets.UTF_8).also { _ -> it.fill(0) } } }

    /**
     * Nouvelle identité : nouvelles clés, nouvelle adresse onion. L'ancien
     * lien ne mène plus nulle part. L'appelant efface contacts et messages,
     * liés à l'ancienne, puis redémarre l'app pour que Tor publie la nouvelle.
     */
    fun replaceIdentity() {
        check(sealKey != null) { "Coffre fermé" }
        saveIdentity(createNewIdentity())
    }

    /** Graine de la clé onion, pour Tor. Copie : l'appelant l'efface. Null coffre fermé. */
    fun onionSeed(): ByteArray? = secrets?.onionSeed?.copyOf()

    /** Notre adresse onion (sans « .onion »), celle du QR. Null coffre fermé. */
    fun onionAddress(): String? = secrets?.onionAddress

    /** Une identité scellée existe : elle ne s'ouvre qu'avec le PIN. */
    fun isSealed(): Boolean = storageService.contains(StorageService.KEY_IDENTITY_SEALED)

    /**
     * Charge l'identité existante ou la génère si elle n'existe pas.
     *
     * Ne crée JAMAIS par-dessus une identité existante mais verrouillée :
     * getIdentity() rend null coffre fermé, et sans ce garde-fou on
     * écraserait l'identité — et tous les contacts avec elle.
     */
    fun getOrCreateIdentity(): Identity {
        getIdentity()?.let { return it }
        check(!isIdentityInitialized()) { "Identité verrouillée : PIN requis" }
        return createNewIdentity().also { saveIdentity(it) }
    }

    /**
     * Crée une nouvelle identité : paire X25519 (chiffrement) + paire
     * Ed25519 (signature).
     */
    private fun createNewIdentity(): Identity {
        val keys = cryptoManager.generateIdentity()
        return Identity(
            publicKeyHex = keys.encryptionPublicKeyHex,
            encryptionPrivateKeyHex = keys.encryptionPrivateKeyHex,
            signingPublicKeyHex = keys.signingPublicKeyHex,
            signingPrivateKeyHex = keys.signingPrivateKeyHex,
            createdAt = System.currentTimeMillis()
        )
    }

    /**
     * Sauvegarde l'identité dans le stockage chiffré.
     */
    fun saveIdentity(identity: Identity) {
        if (sealKey != null) {
            // Nouvelle identité, nouvelle adresse : l'ancienne ne doit plus
            // pouvoir être reliée à la nouvelle.
            seal(identity.encryptionPrivateKeyHex, identity.signingPrivateKeyHex, PinCrypto.randomBytes(32))
        } else {
            // Onboarding, avant le PIN : scellé au premier déverrouillage.
            storageService.saveString(StorageService.KEY_IDENTITY_PRIVKEY, identity.encryptionPrivateKeyHex)
            storageService.saveString(StorageService.KEY_IDENTITY_SIGN_PRIV, identity.signingPrivateKeyHex)
        }
        storageService.saveString(StorageService.KEY_IDENTITY_PUBKEY, identity.publicKeyHex)
        storageService.saveString(StorageService.KEY_IDENTITY_SIGN_PUB, identity.signingPublicKeyHex)
        storageService.saveLong(StorageService.KEY_IDENTITY_CREATED_AT, identity.createdAt)
    }

    /**
     * Vérifie si l'identité est initialisée.
     */
    fun isIdentityInitialized(): Boolean =
        storageService.getString(StorageService.KEY_IDENTITY_PUBKEY).isNotBlank()

    /**
     * Récupère la clé publique actuelle (partageable).
     */
    fun getPublicKeyHex(): String =
        storageService.getString(StorageService.KEY_IDENTITY_PUBKEY)

    /**
     * Récupère la clé privée de chiffrement.
     * À n'utiliser que pour déchiffrer — ne jamais logger ni transmettre.
     */
    fun getEncryptionPrivateKeyHex(): String =
        secrets?.encryptionHex ?: storageService.getString(StorageService.KEY_IDENTITY_PRIVKEY)

    /**
     * Clé privée Ed25519. À n'utiliser que pour signer — ne jamais logger
     * ni transmettre.
     */
    fun getSigningPrivateKeyHex(): String =
        secrets?.signingHex ?: storageService.getString(StorageService.KEY_IDENTITY_SIGN_PRIV)

    /**
     * Récupère l'identité complète.
     */
    fun getIdentity(): Identity? {
        val pubKey = storageService.getString(StorageService.KEY_IDENTITY_PUBKEY)
        if (pubKey.isBlank()) return null

        // Coffre fermé et identité scellée : null, comme si elle n'existait pas.
        val privKey = getEncryptionPrivateKeyHex()
        if (privKey.isBlank()) return null

        val signPub = storageService.getString(StorageService.KEY_IDENTITY_SIGN_PUB)
        val signPriv = getSigningPrivateKeyHex()
        if (signPub.isBlank() || signPriv.isBlank()) return null

        val createdAt = storageService.getLong(StorageService.KEY_IDENTITY_CREATED_AT)

        return Identity(
            publicKeyHex = pubKey,
            encryptionPrivateKeyHex = privKey,
            signingPublicKeyHex = signPub,
            signingPrivateKeyHex = signPriv,
            createdAt = if (createdAt > 0) createdAt else System.currentTimeMillis()
        )
    }

    /**
     * Valide l'identité stockée : format correct ET cohérence clé privée/publique.
     * C'est ce second contrôle qui détecte un stockage corrompu.
     */
    fun validateIdentity(): Boolean {
        val identity = getIdentity() ?: return false
        if (!identity.isValid() || !identity.hasUsablePrivateKey()) return false

        // Les DEUX paires doivent être cohérentes : une clé de signature
        // corrompue rendrait tous nos messages non vérifiables par les autres,
        // sans qu'aucun symptôme n'apparaisse de notre côté.
        return try {
            cryptoManager.derivePublicKey(identity.encryptionPrivateKeyHex) == identity.publicKeyHex &&
                cryptoManager.deriveSigningPublicKey(identity.signingPrivateKeyHex) == identity.signingPublicKeyHex
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Infos d'identité pour l'affichage. Ne contient jamais de secret.
     */
    data class IdentityInfo(
        val publicKeyHex: String,
        val publicKeyAbbrev: String,
        val createdAt: Long,
        val createdAtFormatted: String
    )

    fun getIdentityInfo(): IdentityInfo? {
        val identity = getIdentity() ?: return null

        return IdentityInfo(
            publicKeyHex = identity.publicKeyHex,
            publicKeyAbbrev = identity.publicKeyHex.take(8),
            createdAt = identity.createdAt,
            createdAtFormatted = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                java.util.Locale.getDefault()
            ).format(java.util.Date(identity.createdAt))
        )
    }

    private companion object {
        const val SEALED_SIZE = 96
    }
}
