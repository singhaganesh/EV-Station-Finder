package com.ganesh.stationfinder.data.network

import com.ganesh.stationfinder.data.model.UserProfile
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
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

    suspend fun signInWithGoogle() {
        auth.signInWith(Google)
    }

    suspend fun signOut() {
        auth.signOut()
    }
}
