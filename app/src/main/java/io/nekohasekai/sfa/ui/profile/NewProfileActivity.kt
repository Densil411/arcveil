package io.nekohasekai.sfa.ui.profile

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.EnabledType
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.databinding.ActivityAddProfileBinding
import io.nekohasekai.sfa.ktx.*
import io.nekohasekai.sfa.ui.shared.AbstractActivity
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.lang.Integer.max
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Date

class NewProfileActivity : AbstractActivity<ActivityAddProfileBinding>() {

    companion object {
        const val EXTRA_PROVISION_FLOW = "provisionFlow"      // сразу открыть диалог кода
        const val EXTRA_FORCE_ENTRY_MENU = "forceEntryMenu"   // принудительно показать меню
    }

    // БАЗОВЫЙ URL теперь берём из BuildConfig — задаётся в build.gradle
    private val BASE_URL = BuildConfig.PROVISION_BASE_URL

    enum class FileSource(@StringRes val formattedRes: Int) {
        CreateNew(R.string.profile_source_create_new),
        Import(R.string.profile_source_import);

        fun formatted(context: Context): String = context.getString(formattedRes)
    }

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { fileURI ->
            if (fileURI != null) {
                binding.sourceURL.editText?.setText(fileURI.toString())
            }
        }

    // Флаг и утилита: скрываем всю форму под диалогом кода
    private var formHiddenForProvision = false
    private fun hideFormForProvision() {
        if (formHiddenForProvision) return
        formHiddenForProvision = true

        // Прячем всю форму, чтобы не было видно ручного создания
        binding.root.isVisible = false

        // Ставим фон окна как в теме (а не чёрный).
        // Если фон темы не удастся получить — используем светлый системный.
        val a = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
        try {
            val bg = a.getDrawable(0)
            if (bg != null) {
                window.setBackgroundDrawable(bg)
            } else {
                window.setBackgroundDrawable(
                    ColorDrawable(ContextCompat.getColor(this, android.R.color.background_light))
                )
            }
        } finally {
            a.recycle()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.title_new_profile)

        // Если пришли с готовым URL/именем (например, из QR) — штатный поток
        intent.getStringExtra("importName")?.also { importName ->
            intent.getStringExtra("importURL")?.also { importURL ->
                binding.name.editText?.setText(importName)
                binding.type.text = TypedProfile.Type.Remote.getString(this)
                binding.remoteURL.editText?.setText(importURL)
                binding.localFields.isVisible = false
                binding.remoteFields.isVisible = true
                binding.autoUpdateInterval.text = "60"
            }
        }

        // Что показывать при входе
        when {
            intent.getBooleanExtra(EXTRA_PROVISION_FLOW, false) -> {
                hideFormForProvision()
                showCodeDialog()
                return
            }
            intent.getBooleanExtra(EXTRA_FORCE_ENTRY_MENU, false) -> showEntryMenu()
            savedInstanceState == null && intent.getStringExtra("importURL") == null -> showEntryMenu()
        }

        binding.name.removeErrorIfNotEmpty()
        binding.type.addTextChangedListener {
            when (it) {
                TypedProfile.Type.Local.getString(this) -> {
                    binding.localFields.isVisible = true
                    binding.remoteFields.isVisible = false
                }
                TypedProfile.Type.Remote.getString(this) -> {
                    binding.localFields.isVisible = false
                    binding.remoteFields.isVisible = true
                    if (binding.autoUpdateInterval.text.toIntOrNull() == null) {
                        binding.autoUpdateInterval.text = "60"
                    }
                }
            }
        }
        binding.fileSourceMenu.addTextChangedListener {
            when (it) {
                FileSource.CreateNew.formatted(this) -> {
                    binding.importFileButton.isVisible = false
                    binding.sourceURL.isVisible = false
                }
                FileSource.Import.formatted(this) -> {
                    binding.importFileButton.isVisible = true
                    binding.sourceURL.isVisible = true
                }
            }
        }
        binding.importFileButton.setOnClickListener {
            startFilesForResult(importFile, "application/json")
        }
        binding.createProfile.setOnClickListener(this::createProfile)
        binding.autoUpdateInterval.addTextChangedListener(this::updateAutoUpdateInterval)
    }

    private fun showEntryMenu() {
        // Показываем только один пункт — Denis Shield
        AlertDialog.Builder(this)
            .setTitle("Выберите VPN")
            .setItems(arrayOf("Denis Shield")) { d, _ ->
                d.dismiss()
                showCodeDialog()
            }
            .setCancelable(true)
            .show()
    }

    private fun showCodeDialog() {
        hideFormForProvision() // гарантируем скрытие формы перед показом

        val input = EditText(this).apply {
            hint = "Введите код клиента (10 символов)"
            // Разрешаем латиницу и цифры; без автозамены/предиктивного ввода
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            // Ограничиваем длину поля до 10
            filters = arrayOf(InputFilter.LengthFilter(10))
            maxLines = 1
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Denis Shield")
            .setView(input)
            .setPositiveButton("Подключить") { d, _ ->
                // только латиница/цифры, приведение к lowercase, ровно 10 символов
                val code = input.text
                    .toString()
                    .trim()
                    .lowercase()
                    .filter { it in 'a'..'z' || it in '0'..'9' }

                if (code.length != 10) {
                    Toast.makeText(this, "Код должен содержать 10 символов (a-z, 0-9)", Toast.LENGTH_SHORT).show()
                } else {
                    provisionAndImport(code)
                }
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { d, _ ->
                d.dismiss()
                // не показываем скрытую форму — просто выходим с экрана
                finish()
            }
            .create()

        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    private fun createProfile(@Suppress("UNUSED_PARAMETER") view: View) {
        if (binding.name.showErrorIfEmpty()) return
        when (binding.type.text) {
            TypedProfile.Type.Local.getString(this) -> {
                when (binding.fileSourceMenu.text) {
                    FileSource.Import.formatted(this) -> {
                        if (binding.sourceURL.showErrorIfEmpty()) return
                    }
                }
            }
            TypedProfile.Type.Remote.getString(this) -> {
                if (binding.remoteURL.showErrorIfEmpty()) return
            }
        }
        binding.progressView.isVisible = true
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { createProfile0() }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    binding.progressView.isVisible = false
                    errorDialogBuilder(e).show()
                }
            }
        }
    }

    private suspend fun createProfile0() {
        val typedProfile = TypedProfile()
        val profile = Profile(name = binding.name.text, typed = typedProfile)
        profile.userOrder = ProfileManager.nextOrder()
        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        typedProfile.path = configFile.path

        when (binding.type.text) {
            TypedProfile.Type.Local.getString(this) -> {
                typedProfile.type = TypedProfile.Type.Local
                when (binding.fileSourceMenu.text) {
                    FileSource.CreateNew.formatted(this) -> {
                        configFile.writeText("{}")
                    }
                    FileSource.Import.formatted(this) -> {
                        val sourceURL = binding.sourceURL.text
                        val content = if (sourceURL.startsWith("content://")) {
                            val inputStream =
                                contentResolver.openInputStream(Uri.parse(sourceURL)) as InputStream
                            inputStream.use { it.bufferedReader().readText() }
                        } else if (sourceURL.startsWith("file://")) {
                            File(sourceURL).readText()
                        } else if (sourceURL.startsWith("http://") || sourceURL.startsWith("https://")) {
                            HTTPClient().use { it.getString(sourceURL) }
                        } else {
                            error("unsupported source: $sourceURL")
                        }
                        Libbox.checkConfig(content)
                        configFile.writeText(content)
                    }
                }
            }
            TypedProfile.Type.Remote.getString(this) -> {
                typedProfile.type = TypedProfile.Type.Remote
                val remoteURL = binding.remoteURL.text
                val content = HTTPClient().use { it.getString(remoteURL) }
                Libbox.checkConfig(content)
                configFile.writeText(content)
                typedProfile.remoteURL = remoteURL
                typedProfile.lastUpdated = Date()
                typedProfile.autoUpdate =
                    EnabledType.valueOf(this, binding.autoUpdate.text).boolValue
                binding.autoUpdateInterval.text.toIntOrNull()?.also {
                    typedProfile.autoUpdateInterval = it
                }
            }
        }
        ProfileManager.create(profile)
        withContext(Dispatchers.Main) {
            binding.progressView.isVisible = false
            finish()
        }
    }

    private fun updateAutoUpdateInterval(newValue: String) {
        if (newValue.isBlank()) {
            binding.autoUpdateInterval.error = getString(R.string.profile_input_required)
            return
        }
        val intValue = try {
            newValue.toInt()
        } catch (e: Exception) {
            binding.autoUpdateInterval.error = e.localizedMessage
            return
        }
        if (intValue < 15) {
            binding.autoUpdateInterval.error =
                getString(R.string.profile_auto_update_interval_minimum_hint)
            return
        }
        binding.autoUpdateInterval.error = null
    }

    // ---- backend + импорт ----

    private fun provisionAndImport(code: String) {
        binding.progressView.isVisible = true
        lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { provisionRequest(code) }
                val name = resp.profile_name ?: "Denis Shield"

                withContext(Dispatchers.IO) {
                    when {
                        !resp.config_json.isNullOrBlank() -> importLocalJson(resp.config_json!!, name)
                        !resp.subscription_url.isNullOrBlank() -> importRemoteUrl(resp.subscription_url!!, name)
                        else -> error("Пустой ответ сервера")
                    }
                }

                binding.progressView.isVisible = false
                Toast.makeText(this@NewProfileActivity, "Профиль добавлен", Toast.LENGTH_SHORT).show()
                finish() // успех — возвращаемся к списку профилей
            } catch (e: Exception) {
                binding.progressView.isVisible = false
                // Показываем ошибку и по закрытию диалога уходим назад к списку профилей
                val dlg = errorDialogBuilder(e).show()
                dlg.setOnDismissListener { finish() }
                dlg.setOnCancelListener { finish() }
            }
        }
    }

    private data class ProvisionResp(
        val profile_name: String? = null,
        val config_json: String? = null,
        val subscription_url: String? = null
    )

    private fun deviceId(): String =
        android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )

    private fun provisionRequest(code: String): ProvisionResp {
        val url = URL("$BASE_URL/api/provision")
        val body = """{"code":"$code","device_id":"${deviceId()}"}"""

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        conn.outputStream.use { os ->
            os.write(body.toByteArray(StandardCharsets.UTF_8))
        }

        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

        if (status !in 200..299) {
            val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull().orEmpty()
            val userMsg = when (status) {
                400, 404 -> "Клиент с таким кодом не найден. Проверьте код и попробуйте снова."
                403      -> "Подписка неактивна. Продлите подписку в Telegram‑боте."
                else     -> "Ошибка сервера ($status). ${if (detail.isNotBlank()) detail else "Повторите попытку позже."}"
            }
            throw IllegalStateException(userMsg)
        }

        val o = JSONObject(text)
        return ProvisionResp(
            profile_name = o.optString("profile_name").takeIf { it.isNotBlank() },
            config_json = o.optString("config_json").takeIf { it.isNotBlank() },
            subscription_url = o.optString("subscription_url").takeIf { it.isNotBlank() }
        )
    }

    private suspend fun importLocalJson(json: String, name: String) {
        val typed = TypedProfile().apply { type = TypedProfile.Type.Local }
        val profile = Profile(name = name, typed = typed).apply {
            userOrder = ProfileManager.nextOrder()
        }
        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        typed.path = configFile.path

        Libbox.checkConfig(json)
        configFile.writeText(json)
        ProfileManager.create(profile)
        // здесь можно инициировать автозапуск VPN, если нужно
    }

    private suspend fun importRemoteUrl(url: String, name: String) {
        val typed = TypedProfile().apply {
            type = TypedProfile.Type.Remote
            remoteURL = url
            lastUpdated = Date()
            autoUpdate = true
            autoUpdateInterval = max(15, 60)
        }
        val profile = Profile(name = name, typed = typed).apply {
            userOrder = ProfileManager.nextOrder()
        }
        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        typed.path = configFile.path

        val content = HTTPClient().use { it.getString(url) }
        Libbox.checkConfig(content)
        configFile.writeText(content)
        ProfileManager.create(profile)
    }
}
