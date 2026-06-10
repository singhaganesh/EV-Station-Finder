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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object AuthManager {
    private val auth get() = SupabaseProvider.auth

    fun currentAccessToken(): String? {
        return try {
            auth.currentAccessTokenOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Force-refresh the Supabase session and return the new access token, or null
     * if refresh failed (e.g. the refresh token itself expired). Called by the
     * OkHttp Authenticator on a 401 to transparently recover from token expiry.
     */
    suspend fun refreshSession(): String? {
        return try {
            auth.refreshCurrentSession()
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
        val displayName = user.userMetadata?.get("name")?.jsonPrimitive?.contentOrNull
            ?: user.userMetadata?.get("full_name")?.jsonPrimitive?.contentOrNull
            ?: "User"
        val avatarUrl = user.userMetadata?.get("avatar_url")?.jsonPrimitive?.contentOrNull
            ?: user.userMetadata?.get("picture")?.jsonPrimitive?.contentOrNull
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

    suspend fun updateProfileInSupabase(newName: String, newAvatarUrl: String?) {
        auth.updateUser {
            data {
                put("name", newName)
                if (newAvatarUrl != null) {
                    put("avatar_url", newAvatarUrl)
                }
            }
        }
    }

    suspend fun uploadAvatar(context: Context, imageUri: android.net.Uri): String {
        val user = currentUser() ?: throw Exception("User not signed in")
        val bucket = SupabaseProvider.storage.from("avatars")

        // Validate MIME type before reading.
        val mime = context.contentResolver.getType(imageUri)
        if (mime != null && !mime.startsWith("image/")) {
            throw Exception("Please select an image file")
        }

        // Read bytes from gallery Uri and reject oversized source files.
        val rawBytes = context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
            ?: throw Exception("Failed to read image data")
        if (rawBytes.size > MAX_SOURCE_BYTES) {
            throw Exception("Image is too large (max 15 MB)")
        }

        // Downscale + recompress so we never upload a huge file to the public bucket.
        val bytes = compressImage(rawBytes)

        // Generate a unique filename using timestamp to bust image caches
        val fileName = "${user.id}/avatar_${System.currentTimeMillis()}.jpg"

        // 1. Delete any old avatars in the user's folder to clean up space
        try {
            val files = bucket.list(user.id)
            if (files.isNotEmpty()) {
                bucket.delete(files.map { "${user.id}/${it.name}" })
            }
        } catch (ignored: Exception) {}

        // 2. Upload the new file
        bucket.upload(fileName, bytes) {
            upsert = true
        }

        // 3. Retrieve and return the public URL
        return bucket.publicUrl(fileName)
    }

    private const val MAX_SOURCE_BYTES = 15 * 1024 * 1024
    private const val MAX_AVATAR_DIMENSION = 1024

    /** Downscale to at most MAX_AVATAR_DIMENSION on the longest edge and JPEG-compress. */
    private fun compressImage(input: ByteArray): ByteArray {
        val original = android.graphics.BitmapFactory.decodeByteArray(input, 0, input.size)
            ?: return input // not decodable as a bitmap; let upstream validation handle it
        val w = original.width
        val h = original.height
        val scale = kotlin.math.min(1f, MAX_AVATAR_DIMENSION.toFloat() / kotlin.math.max(w, h))
        val scaled = if (scale < 1f) {
            android.graphics.Bitmap.createScaledBitmap(original, (w * scale).toInt(), (h * scale).toInt(), true)
        } else {
            original
        }
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }
}
