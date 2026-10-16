package com.whisper.theme

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.whisper.services.StorageService

/**
 * La langue de Whospair, choisie dans Réglages › Langue, indépendamment de
 * celle du téléphone. Vide : la langue du téléphone.
 *
 * Android 13 et plus : le système garde le choix (LocaleManager) et recrée
 * l'activité. Android 10 à 12 : le choix est gardé chiffré (StorageService)
 * et appliqué au contexte de l'activité, qu'on recrée.
 */
object AppLanguage {
    const val KEY = "app_language"

    /** Étiquette BCP 47 et nom dans sa propre langue ; jamais traduit. */
    val options: List<Pair<String, String>> = listOf(
        "fr" to "Français",
        "en" to "English",
        "es" to "Español",
        "pt" to "Português",
        "de" to "Deutsch",
        "it" to "Italiano",
        "hr" to "Hrvatski",
        "sr" to "Српски",
        "sr-Latn" to "Srpski (latinica)",
        "sk" to "Slovenčina",
        "uk" to "Українська",
        "ru" to "Русский",
        "tr" to "Türkçe",
        "ar" to "العربية",
        "fa" to "فارسی",
        "he" to "עברית",
        "vi" to "Tiếng Việt",
        "zh-Hans" to "简体中文",
        "zh-Hant" to "繁體中文",
        "ja" to "日本語",
        "ko" to "한국어"
    )

    fun nameOf(tag: String): String? = options.firstOrNull { it.first == tag }?.second

    /** Le choix en cours ; "" = langue du téléphone. */
    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
                .split(',').firstOrNull().orEmpty()
                .let { tag -> options.firstOrNull { it.first.equals(tag, ignoreCase = true) }?.first ?: tag }
        } else {
            runCatching { StorageService(context).getString(KEY) }.getOrDefault("")
        }

    fun set(activity: Activity, storage: StorageService, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            // Le système recrée l'activité de lui-même.
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            storage.saveString(KEY, tag)
            activity.recreate()
        }
    }

    /** Android 10 à 12 : le contexte de l'activité, dans la langue choisie. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = runCatching { StorageService(base).getString(KEY) }.getOrDefault("")
        if (tag.isEmpty()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(config)
    }
}
