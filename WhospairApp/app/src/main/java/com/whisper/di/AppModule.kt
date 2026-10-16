package com.whisper.di

import android.content.Context
import com.whisper.crypto.CryptoManager
import com.whisper.security.Vault
import com.whisper.repositories.ContactRepository
import com.whisper.repositories.MessageRepository
import com.whisper.services.IdentityService
import com.whisper.services.StorageService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Module Hilt pour l'injection de dépendances
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Fournit l'instance unique de CryptoManager
     */
    @Singleton
    @Provides
    fun provideCryptoManager(): CryptoManager = CryptoManager()

    /**
     * Fournit l'instance unique de StorageService
     */
    @Singleton
    @Provides
    fun provideStorageService(
        @ApplicationContext context: Context
    ): StorageService = StorageService(context)

    /**
     * Fournit l'instance unique de IdentityService
     */
    @Singleton
    @Provides
    fun provideIdentityService(
        storageService: StorageService,
        cryptoManager: CryptoManager
    ): IdentityService = IdentityService(storageService, cryptoManager)

    /**
     * Fournit l'instance unique de ContactRepository
     */
    @Singleton
    @Provides
    fun provideContactRepository(vault: Vault): ContactRepository = ContactRepository(vault)

    /**
     * Fournit l'instance unique de MessageRepository
     */
    @Singleton
    @Provides
    fun provideMessageRepository(vault: Vault): MessageRepository = MessageRepository(vault)
}
