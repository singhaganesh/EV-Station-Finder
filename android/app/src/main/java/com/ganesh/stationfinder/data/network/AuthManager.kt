package com.ganesh.stationfinder.data.network

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.ganesh.stationfinder.BuildConfig
import com.ganesh.stationfinder.data.model.UserProfile
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.user.UserInfo

object AuthManager {
    private val auth get() = SupabaseProvider.auth

    fun currentAccessToken(): String? {
        return try {
            auth.currentAccessTokenOrNull()
        } catch (e: Exception) {
            null
        }
    }

    fun currentUser(): UserInfo? {
        return try {
            auth.currentUserOrNull()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun currentProfile(): UserProfile? {
        val user = currentUser() ?: return null
        val displayName = user.userMetadata?.get("name")?.toString() 
            ?: user.userMetadata?.get("full_name")?.toString() 
            ?: "User"
        val avatarUrl = user.userMetadata?.get("avatar_url")?.toString() 
            ?: user.userMetadata?.get("picture")?.toString()
        return UserProfile(
            id = user.id,
            displayName = displayName,
            email = user.email,
            avatarUrl = avatarUrl
        )
    }

    suspend fun signInWithGoogle(context: Context) {
        val credentialManager = CredentialManager.create(context)
        
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val result = credentialManager.getCredential(
            context = context,
            request = request
        )

        val credential = result.credential
        if (credential is androidx.credentials.CustomCredential && 
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val idToken = googleIdTokenCredential.idToken
            
            auth.signInWith(IDToken) {
                this.idToken = idToken
                this.provider = Google
            }
        } else {
            throw Exception("Received invalid credential type")
        }
    }

    suspend fun signOut() {
        auth.signOut()
    }
}
