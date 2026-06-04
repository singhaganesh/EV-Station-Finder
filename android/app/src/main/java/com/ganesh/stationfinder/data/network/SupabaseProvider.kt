package com.ganesh.stationfinder.data.network

import com.ganesh.stationfinder.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth

object SupabaseProvider {
    private fun cleanUrl(url: String): String {
        return url.trim().removeSuffix("/").removeSuffix("/rest/v1").removeSuffix("/rest/v1/")
    }

    val client = createSupabaseClient(
        supabaseUrl = cleanUrl(BuildConfig.SUPABASE_URL),
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY
    ) {
        install(Auth)
    }
    
    val auth = client.auth
}
