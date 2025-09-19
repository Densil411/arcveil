package io.nekohasekai.sfa.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Проверка обновлений приложения на вашем сервере.
 *
 * Эндпоинт: ${BuildConfig.UPDATE_BASE_URL}/api/app/latest?platform=android
 * Ожидаемый JSON:
 * {
 *   "version_code": 552,
 *   "version_name": "1.12.2",
 *   "min_supported_code": 520,
 *   "download_url": "https://.../DenisShield-1.12.2.apk",
 *   "changelog": "• Исправления\n• Улучшения"
 * }
 */
object UpdateChecker {

    private fun endpoint() =
        "${BuildConfig.UPDATE_BASE_URL.trimEnd('/')}/api/app/latest?platform=android"

    fun checkNow(activity: FragmentActivity) {
        activity.lifecycleScope.launch(Dispatchers.Main) {
            val text = runCatching {
                withContext(Dispatchers.IO) { HTTPClient().use { it.getString(endpoint()) } }
            }.getOrElse { e ->
                showError(activity, activity.getString(R.string.update_check_failed, e.localizedMessage ?: ""))
                return@launch
            }

            val o = runCatching { JSONObject(text) }.getOrElse {
                showError(activity, activity.getString(R.string.update_invalid_response))
                return@launch
            }

            val serverCode = o.optInt("version_code", -1)
            val serverName = o.optString("version_name").ifBlank { serverCode.toString() }
            val minSupported = o.optInt("min_supported_code", -1)
            val url = o.optString("download_url")
            val changelog = o.optString("changelog")

            val localCode = BuildConfig.VERSION_CODE
            val localName = BuildConfig.VERSION_NAME

            if (minSupported > 0 && localCode < minSupported && url.isNotBlank()) {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.update_required_title)
                    .setMessage(
                        activity.getString(R.string.update_required_message, localName, serverName) +
                                (if (changelog.isNotBlank()) "\n\n$changelog" else "")
                    )
                    .setPositiveButton(R.string.update_action_download) { _, _ -> openUrl(activity, url) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return@launch
            }

            if (serverCode > localCode && url.isNotBlank()) {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.update_available_title)
                    .setMessage(
                        activity.getString(R.string.update_available_message, localName, serverName) +
                                (if (changelog.isNotBlank()) "\n\n$changelog" else "")
                    )
                    .setPositiveButton(R.string.update_action_download) { _, _ -> openUrl(activity, url) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                AlertDialog.Builder(activity)
                    .setMessage(R.string.update_latest)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun showError(ctx: Context, msg: String) {
        AlertDialog.Builder(ctx)
            .setTitle(R.string.update_error_title)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
