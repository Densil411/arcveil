package io.nekohasekai.sfa.provision

import android.content.Context
import android.provider.Settings
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

data class ProvisionReq(val code: String, val device_id: String)
data class ProvisionResp(
    val profile_name: String? = null,
    val config_json: String? = null,
    val subscription_url: String? = null
)

interface ProvisionApi {
    @POST("/api/provision")
    suspend fun provision(@Body body: ProvisionReq): ProvisionResp
}

object Provision {
    // В DEV поставь http://10.0.2.2:8000, в проде — свой домен
    const val BASE_URL = "http://10.0.2.2:8000"

    fun api(): ProvisionApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(MoshiConverterFactory.create())
        .build()
        .create(ProvisionApi::class.java)

    fun deviceId(ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
}
