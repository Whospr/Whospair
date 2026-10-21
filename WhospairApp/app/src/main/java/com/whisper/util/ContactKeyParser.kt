package com.whisper.util

import com.whisper.tor.OnionAddress

/**
 * ContactKeyParser - Extrait une identité publique depuis ce qu'on lui donne.
 *
 * Une identité, c'est deux clés et une adresse, concaténées :
 *   X25519 (64 hex, chiffrement) ‖ Ed25519 (64 hex, signatures)
 *   ‖ adresse onion v3 (56 base32, où joindre le contact) = 184 caractères.
 *
 * L'adresse onion est obligatoire : sans serveur, c'est le seul moyen de
 * joindre quelqu'un. Un contact sans adresse serait injoignable, donc on ne
 * l'accepte pas. Sa somme de contrôle est vérifiée : une faute de frappe dans
 * un collage à la main est refusée, pas enregistrée.
 *
 * Trois formes circulent, et elles ne sont PAS interchangeables côté Android :
 *
 *  1. `whospair:<184>` — ce qu'encode le QR. URI opaque, sans autorité.
 *
 *  2. `whospair://add_contact?key=<184>` — lien partageable et cliquable.
 *     URI hiérarchique, seule forme que le filtre d'intent du manifeste
 *     (scheme + host) peut capter. Un QR contenant la forme 1 ne déclenche
 *     PAS ce filtre — d'où la nécessité de gérer les deux.
 *
 *  3. `<184>` brut — collé à la main depuis un message ou un mail.
 *
 *  4. `https://whospair.github.io/a#<184>` — lien d'invitation. Seule forme
 *     que WhatsApp, Signal ou un SMS rendent cliquable. La clé est APRÈS le
 *     `#` : un navigateur n'envoie jamais le fragment au serveur, la page
 *     ne voit donc ni qui invite ni qui est invité. Sans l'app, la page
 *     explique comment l'obtenir ; avec, Android ouvre l'app directement.
 *
 * Toutes convergent vers la même identité, normalisée en minuscules.
 * L'ancien préfixe `whisper:` (avant le nom Whospair) est toujours lu.
 */
object ContactKeyParser {

    const val SCHEME = "whospair"
    /** Liens et QR d'avant le nom Whospair : encore acceptés, plus produits. */
    const val LEGACY_SCHEME = "whisper"
    const val DEEP_LINK_HOST = "add_contact"
    const val KEY_PARAM = "key"
    /** Page d'invitation. Doit rester alignée sur le filtre https du manifeste. */
    const val WEB_LINK_PREFIX = "https://whospair.github.io/a#"

    /** X25519 (64) + Ed25519 (64) en hex. */
    private const val KEY_LENGTH = 128
    private const val SINGLE_KEY_LENGTH = 64
    private const val PAYLOAD_LENGTH = KEY_LENGTH + OnionAddress.LENGTH
    private val HEX_REGEX = Regex("^[0-9a-fA-F]{$KEY_LENGTH}$")

    /**
     * Résultat d'une tentative de lecture.
     */
    sealed class Result {
        data class Valid(
            val publicKeyHex: String,
            val signingPublicKeyHex: String,
            /** Sans « .onion ». */
            val onionAddress: String
        ) : Result()

        /** Forme reconnue mais clé mal formée. */
        object MalformedKey : Result()

        /** Rien d'exploitable : QR d'un autre produit, texte quelconque. */
        object NotAWhisperKey : Result()
    }

    /**
     * Analyse une entrée brute.
     *
     * Volontairement tolérant sur la casse et les espaces : une clé collée
     * depuis un mail traîne souvent un retour à la ligne ou une majuscule,
     * et refuser pour ça n'apporte aucune sécurité — seulement de la friction.
     */
    fun parse(raw: String?): Result {
        val input = raw?.trim()?.replace("\\s".toRegex(), "") ?: return Result.NotAWhisperKey
        if (input.isEmpty()) return Result.NotAWhisperKey

        // Forme 3 : collage brut
        if (input.length == PAYLOAD_LENGTH) return validate(input)

        val lower = input.lowercase()

        // Forme 4 : lien d'invitation web, cherché partout — on colle souvent
        // le message d'invitation entier, pas seulement le lien.
        // Cherché dans l'entrée elle-même : lowercase() peut allonger un texte
        // (« İ » → 2 caractères) et décaler l'indice.
        val web = input.indexOf(WEB_LINK_PREFIX, ignoreCase = true)
        if (web >= 0) return validate(input.substring(web + WEB_LINK_PREFIX.length).take(PAYLOAD_LENGTH))

        for (scheme in listOf(SCHEME, LEGACY_SCHEME)) {
            // Forme 2 : lien profond hiérarchique
            if (lower.startsWith("$scheme://$DEEP_LINK_HOST")) {
                val key = input
                    .substringAfter("$KEY_PARAM=", missingDelimiterValue = "")
                    .substringBefore('&')
                return validate(key)
            }

            // Forme 1 : URI opaque du QR
            if (lower.startsWith("$scheme:")) {
                return validate(input.drop(scheme.length + 1).removePrefix("//"))
            }
        }

        return Result.NotAWhisperKey
    }

    private fun validate(candidate: String): Result {
        if (candidate.length != PAYLOAD_LENGTH) return Result.MalformedKey
        val lower = candidate.lowercase()
        val keys = lower.take(KEY_LENGTH)
        val onion = lower.drop(KEY_LENGTH)
        if (!keys.matches(HEX_REGEX) || !OnionAddress.isValid(onion)) return Result.MalformedKey
        return Result.Valid(
            publicKeyHex = keys.take(SINGLE_KEY_LENGTH),
            signingPublicKeyHex = keys.drop(SINGLE_KEY_LENGTH),
            onionAddress = onion
        )
    }

    private fun payload(publicKeyHex: String, signingPublicKeyHex: String, onionAddress: String) =
        publicKeyHex.lowercase() + signingPublicKeyHex.lowercase() + onionAddress.lowercase()

    /**
     * Payload du QR. Forme compacte : moins de caractères = QR moins dense,
     * donc plus tolérant à un mauvais angle ou un écran sale.
     */
    fun toQrPayload(publicKeyHex: String, signingPublicKeyHex: String, onionAddress: String): String =
        "$SCHEME:" + payload(publicKeyHex, signingPublicKeyHex, onionAddress)

    /**
     * Lien partageable par message, mail ou presse-papiers : forme 4, la seule
     * que les messageries rendent cliquable. Les liens `whospair://` déjà
     * envoyés restent lus.
     */
    fun toShareableLink(publicKeyHex: String, signingPublicKeyHex: String, onionAddress: String): String =
        WEB_LINK_PREFIX + payload(publicKeyHex, signingPublicKeyHex, onionAddress)

    /**
     * Empreinte courte d'UNE clé, pour l'affichage compact dans les listes.
     *
     * ⚠️ Ce n'est PAS une vérification d'identité : Alice et Bob voient deux
     * empreintes différentes et n'ont donc rien à comparer. Pour vérifier
     * qu'aucun tiers ne s'est interposé, utiliser
     * MessageService.safetyNumber(), qui produit le même nombre des deux côtés.
     */
    fun fingerprint(publicKeyHex: String): String =
        if (publicKeyHex.length < 16) publicKeyHex
        else "${publicKeyHex.take(8)}…${publicKeyHex.takeLast(8)}".uppercase()
}
