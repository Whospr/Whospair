package com.whisper.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import com.whisper.util.DebugLog

/**
 * Room Database pour Whisper — chiffrée de bout en bout par SQLCipher.
 *
 * Le chiffrement applicatif des messages ne suffisait pas : le contenu était
 * bien du ciphertext, mais TOUT le reste était en clair dans le fichier —
 * qui a parlé à qui, quand, combien de fois, les noms de contacts, les
 * horodatages. Pour quelqu'un sous surveillance, ce graphe social est souvent
 * plus incriminant que le contenu lui-même.
 *
 * SQLCipher chiffre le fichier ENTIER : pages de données, index, journal.
 * Sans la passphrase, il ne reste que des octets aléatoires.
 */
/**
 * Version 2 : suppression des colonnes `isOnline` et `lastConnectionAt` de la
 * table contacts. Aucune migration écrite — `fallbackToDestructiveMigration()`
 * s'en charge, et c'est ici le comportement souhaité : une migration
 * conserverait les anciennes valeurs de `lastConnectionAt`, c'est-à-dire
 * exactement l'historique de fréquentation qu'on cherche à faire disparaître.
 * On préfère perdre le carnet que traîner le journal.
 *
 * Version 3 : colonne `onionAddress` (Tor seul, sans serveur). Destructif
 * aussi : un contact d'avant n'a pas d'adresse, il serait injoignable — il
 * faut de toute façon le rescanner.
 *
 * Version 4 : retrait des clés étrangères de `messages` (voir MessageEntity).
 *
 * Version 5 : colonne `isTrusted` (contact venu seul, sans scan de notre part).
 *
 * Version 6 : `contacts.readLock` (masquage de la lecture) et
 * `messages.selfCopy` (notre copie chiffrée d'un envoi).
 *
 * Version 7 : `contacts.photo`. Première vraie migration : ajouter une
 * colonne vide ne réveille aucune trace, et évite de rescanner tout le monde.
 *
 * Version 8 : `contacts.isPrivate` (mode privé par conversation), par migration.
 *
 * Version 9 : `contacts.ttlMinutes` (durée de vie gardée par conversation), par migration.
 * Version 10 : `contacts.isVerified` (vérifié en personne) et l'effacement à deux
 *   (`wipeState`, `wipeOutbox`, `wipePending`), par migration (1.3.7).
 * Version 11 : `messages.receivedAt` (heure de réception, notre horloge) et
 *   `contacts.isPinned` (conversation épinglée), par migration (1.3.8).
 */
@Database(
    entities = [ContactEntity::class, MessageEntity::class],
    version = 11,
    exportSchema = false
)
abstract class WhisperDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao

    companion object {
        private const val TAG = "WhisperDatabase"
        const val DATABASE_NAME = "whisper_database"

        /**
         * Construit la base chiffrée.
         *
         * @param passphrase 32 octets. SQLCipher REMET CE TABLEAU À ZÉRO après
         *        ouverture — l'appelant doit donc fournir une copie fraîche
         *        (le coffre fournit une copie et efface la sienne).
         */
        fun build(context: Context, passphrase: ByteArray): WhisperDatabase {
            // Le binaire natif doit être chargé avant toute ouverture.
            System.loadLibrary("sqlcipher")

            val factory = SupportOpenHelperFactory(passphrase)

            return Room.databaseBuilder(
                context.applicationContext,
                WhisperDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(factory)
                .addCallback(securityPragmas)
                .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                .fallbackToDestructiveMigration()
                .build()
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN photo BLOB")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN isPrivate INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN ttlMinutes INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN isVerified INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE contacts ADD COLUMN wipeState INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE contacts ADD COLUMN wipeOutbox INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE contacts ADD COLUMN wipePending INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN receivedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE contacts ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * PRAGMA appliqués à chaque ouverture.
         */
        private val securityPragmas = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)

                // Sans secure_delete, un DELETE se contente de marquer la page
                // comme libre : les octets supprimés restent sur le disque
                // jusqu'à réécriture. C'est ce qui rendait l'expiration TTL
                // purement cosmétique face à une analyse du support.
                //
                // Les PRAGMA passent par query(), jamais execSQL() : ceux qui
                // renvoient une ligne font lever une exception à SQLCipher
                // sous execSQL() — c'était le plantage à la validation du PIN.
                pragma(db, "PRAGMA secure_delete = ON")

                // Efface les tampons mémoire de SQLCipher dès qu'ils sont
                // libérés, pour limiter ce qu'un dump de RAM révélerait.
                pragma(db, "PRAGMA cipher_memory_security = ON")

                // Sans droit mlock (tout appareil non rooté), SQLCipher
                // journalise un échec à chaque tampon : des milliers de lignes
                // par session, qui noyaient les vraies erreurs. On ne garde
                // que les avertissements et les erreurs.
                pragma(db, "PRAGMA cipher_log_level = WARN")

                // Les clés étrangères ne sont pas actives par défaut sous
                // SQLite : sans ça, le ON DELETE CASCADE de MessageEntity vers
                // ContactEntity ne s'applique jamais et supprimer un contact
                // laisse ses messages orphelins dans la base.
                pragma(db, "PRAGMA foreign_keys = ON")

                DebugLog.d(TAG) { "Base ouverte (chiffrée, secure_delete actif)" }
            }
        }

        private fun pragma(db: SupportSQLiteDatabase, sql: String) {
            db.query(sql).close()
        }

        /**
         * Supprime une base héritée NON chiffrée.
         *
         * SQLCipher ne sait pas ouvrir un fichier en clair : sans ce ménage,
         * une installation antérieure à cette version ferait planter le
         * démarrage. Les données concernées sont éphémères par conception,
         * les perdre est le comportement attendu.
         */
        fun deleteLegacyPlaintextDatabase(context: Context): Boolean {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (!dbFile.exists()) return false

            val deleted = context.deleteDatabase(DATABASE_NAME)
            if (deleted) DebugLog.w(TAG, "Base en clair héritée supprimée")
            return deleted
        }
    }
}
