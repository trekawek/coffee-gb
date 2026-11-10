package eu.rekawek.coffeegb.swing

import java.net.URI
import java.util.Locale
import java.util.prefs.Preferences

internal enum class ScreenTranslationProvider(
    val displayName: String,
    val requiresApiKey: Boolean,
    val supportsCustomBaseUrl: Boolean,
    val supportsModelSelection: Boolean,
    val apiKeyCreationUrl: String?,
) {
  GEMINI(
      displayName = "Google Gemini",
      requiresApiKey = true,
      supportsCustomBaseUrl = false,
      supportsModelSelection = true,
      apiKeyCreationUrl = "https://aistudio.google.com/apikey",
  ),
  OPENAI_COMPATIBLE(
      displayName = "OpenAI",
      requiresApiKey = true,
      supportsCustomBaseUrl = false,
      supportsModelSelection = true,
      apiKeyCreationUrl = "https://platform.openai.com/api-keys",
  ),
  OLLAMA(
      displayName = "Ollama (Local)",
      requiresApiKey = false,
      supportsCustomBaseUrl = true,
      supportsModelSelection = true,
      apiKeyCreationUrl = null,
  ),
}

internal enum class ResolvedTranslationProtocol {
  GEMINI,
  OPENAI_COMPATIBLE,
}

internal data class ResolvedScreenTranslationConfig(
    val protocol: ResolvedTranslationProtocol,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val targetLanguage: String,
    val pauseWhileTranslating: Boolean,
)

internal data class ScreenTranslationSettings(
    val provider: ScreenTranslationProvider = ScreenTranslationProvider.GEMINI,
    val geminiApiKey: String = "",
    val geminiModel: String = "",
    val openAiApiKey: String = "",
    val openAiModel: String = "",
    val ollamaBaseUrl: String = "",
    val ollamaModel: String = "",
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val targetLanguage: String = DEFAULT_TARGET_LANGUAGE,
    val pauseWhileTranslating: Boolean = false,
) {
  fun sanitized(): ScreenTranslationSettings {
    val cleanGeminiKey = geminiApiKey.trim().take(MAX_FIELD_LENGTH)
    val cleanGeminiModel = geminiModel.trim().take(MAX_MODEL_LENGTH)
    val cleanOpenAiKey = openAiApiKey.trim().take(MAX_FIELD_LENGTH)
    val cleanOpenAiModel = openAiModel.trim().take(MAX_MODEL_LENGTH)
    val cleanOllamaUrl = ollamaBaseUrl.trim().trimEnd('/').take(MAX_FIELD_LENGTH)
    val cleanOllamaModel = ollamaModel.trim().take(MAX_MODEL_LENGTH)

    val legacyKey = apiKey.trim().take(MAX_FIELD_LENGTH)
    val legacyUrl = baseUrl.trim().trimEnd('/').take(MAX_FIELD_LENGTH)
    val legacyModel = model.trim().take(MAX_MODEL_LENGTH)

    val hasPerProviderKey = cleanGeminiKey.isNotEmpty() || cleanOpenAiKey.isNotEmpty()
    val effectiveGeminiKey =
        cleanGeminiKey.ifEmpty {
          if (!hasPerProviderKey &&
              (provider == ScreenTranslationProvider.GEMINI || isGeminiKeyPrefix(legacyKey))) {
            legacyKey
          } else {
            ""
          }
        }
    val effectiveOpenAiKey =
        cleanOpenAiKey.ifEmpty {
          if (!hasPerProviderKey &&
              (provider == ScreenTranslationProvider.OPENAI_COMPATIBLE ||
                  (legacyKey.isNotEmpty() && !isGeminiKeyPrefix(legacyKey)))) {
            legacyKey
          } else {
            ""
          }
        }

    val hasPerProviderModel =
        cleanGeminiModel.isNotEmpty() ||
            cleanOpenAiModel.isNotEmpty() ||
            cleanOllamaModel.isNotEmpty()
    val effectiveGeminiModel =
        cleanGeminiModel.ifEmpty {
          if (!hasPerProviderModel &&
              provider == ScreenTranslationProvider.GEMINI &&
              !isOllamaModel(legacyModel)) {
            legacyModel
          } else {
            ""
          }
        }
    val effectiveOpenAiModel =
        cleanOpenAiModel.ifEmpty {
          if (!hasPerProviderModel &&
              provider == ScreenTranslationProvider.OPENAI_COMPATIBLE &&
              !isOllamaModel(legacyModel)) {
            legacyModel
          } else {
            ""
          }
        }
    val effectiveOllamaModel =
        cleanOllamaModel.ifEmpty {
          if (!hasPerProviderModel &&
              (provider == ScreenTranslationProvider.OLLAMA || isOllamaModel(legacyModel))) {
            legacyModel
          } else {
            ""
          }
        }

    val effectiveOllamaUrl =
        cleanOllamaUrl.ifEmpty {
          if (provider == ScreenTranslationProvider.OLLAMA || isOllamaEndpoint(legacyUrl)) {
            legacyUrl
          } else {
            ""
          }
        }

    val activeApiKey =
        when (provider) {
          ScreenTranslationProvider.GEMINI -> effectiveGeminiKey
          ScreenTranslationProvider.OPENAI_COMPATIBLE -> effectiveOpenAiKey
          ScreenTranslationProvider.OLLAMA -> ""
        }
    val activeBaseUrl =
        when (provider) {
          ScreenTranslationProvider.OLLAMA -> effectiveOllamaUrl
          ScreenTranslationProvider.GEMINI,
          ScreenTranslationProvider.OPENAI_COMPATIBLE ->
              legacyUrl.takeUnless { isOllamaEndpoint(it) }.orEmpty()
        }
    val activeModel =
        when (provider) {
          ScreenTranslationProvider.GEMINI -> effectiveGeminiModel
          ScreenTranslationProvider.OPENAI_COMPATIBLE -> effectiveOpenAiModel
          ScreenTranslationProvider.OLLAMA -> effectiveOllamaModel
        }

    return copy(
        geminiApiKey = effectiveGeminiKey,
        geminiModel = effectiveGeminiModel,
        openAiApiKey = effectiveOpenAiKey,
        openAiModel = effectiveOpenAiModel,
        ollamaBaseUrl = effectiveOllamaUrl,
        ollamaModel = effectiveOllamaModel,
        apiKey = activeApiKey,
        baseUrl = activeBaseUrl,
        model = activeModel,
        targetLanguage =
            targetLanguage.trim().take(MAX_LANGUAGE_LENGTH).ifEmpty { DEFAULT_TARGET_LANGUAGE },
    )
  }

  fun resolve(): ResolvedScreenTranslationConfig? {
    val clean = sanitized()
    val protocol =
        when (clean.provider) {
          ScreenTranslationProvider.GEMINI -> ResolvedTranslationProtocol.GEMINI
          ScreenTranslationProvider.OPENAI_COMPATIBLE,
          ScreenTranslationProvider.OLLAMA -> ResolvedTranslationProtocol.OPENAI_COMPATIBLE
        }

    val resolvedBaseUrl =
        when (clean.provider) {
          ScreenTranslationProvider.GEMINI ->
              clean.baseUrl.takeUnless { isOllamaEndpoint(it) }.orEmpty().ifEmpty {
                DEFAULT_GEMINI_BASE_URL
              }
          ScreenTranslationProvider.OPENAI_COMPATIBLE ->
              clean.baseUrl.takeUnless { isOllamaEndpoint(it) }.orEmpty().ifEmpty {
                DEFAULT_OPENAI_BASE_URL
              }
          ScreenTranslationProvider.OLLAMA ->
              clean.ollamaBaseUrl.ifEmpty { DEFAULT_OLLAMA_BASE_URL }
        }

    val resolvedModel =
        when (clean.provider) {
          ScreenTranslationProvider.GEMINI ->
              clean.geminiModel.ifEmpty { DEFAULT_GEMINI_MODEL }
          ScreenTranslationProvider.OPENAI_COMPATIBLE ->
              clean.openAiModel.ifEmpty { DEFAULT_OPENAI_MODEL }
          ScreenTranslationProvider.OLLAMA ->
              clean.ollamaModel.ifEmpty { DEFAULT_OLLAMA_MODEL }
        }

    val resolvedKey =
        when (clean.provider) {
          ScreenTranslationProvider.GEMINI -> clean.geminiApiKey
          ScreenTranslationProvider.OPENAI_COMPATIBLE -> clean.openAiApiKey
          ScreenTranslationProvider.OLLAMA -> ""
        }

    if (clean.provider.requiresApiKey &&
        isOfficialCloudEndpoint(resolvedBaseUrl) &&
        resolvedKey.isEmpty()) {
      return null
    }

    return ResolvedScreenTranslationConfig(
        protocol = protocol,
        apiKey = resolvedKey,
        baseUrl = resolvedBaseUrl,
        model = resolvedModel,
        targetLanguage = clean.targetLanguage,
        pauseWhileTranslating = clean.pauseWhileTranslating,
    )
  }

  companion object {
    const val DEFAULT_TARGET_LANGUAGE = "English"
    const val DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
    const val DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434/v1"
    const val DEFAULT_GEMINI_MODEL = "models/gemini-3.8-flash"
    const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
    const val DEFAULT_OLLAMA_MODEL = "qwen2.5vl:7b"
    private const val MAX_FIELD_LENGTH = 512
    private const val MAX_MODEL_LENGTH = 128
    private const val MAX_LANGUAGE_LENGTH = 64

    internal fun isLocalEndpoint(baseUrl: String): Boolean {
      val host =
          runCatching { URI(baseUrl).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
      return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
    }

    internal fun isOfficialCloudEndpoint(baseUrl: String): Boolean {
      val host =
          runCatching { URI(baseUrl).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
      return host == "generativelanguage.googleapis.com" || host == "api.openai.com"
    }

    internal fun isOllamaEndpoint(baseUrl: String): Boolean {
      if (baseUrl.isBlank()) return false
      val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return false
      return uri.port == 11434 || uri.path?.contains("/api/chat") == true
    }

    internal fun isOllamaModel(model: String): Boolean {
      if (model.isBlank()) return false
      val lower = model.trim().lowercase(Locale.ROOT)
      return ':' in lower ||
          lower.startsWith("qwen") ||
          lower.startsWith("llama") ||
          lower.startsWith("llava") ||
          lower.startsWith("minicpm") ||
          lower.startsWith("moondream")
    }

    internal fun isGeminiKeyPrefix(apiKey: String): Boolean {
      val trimmed = apiKey.trim()
      return trimmed.startsWith("AIza") || trimmed.startsWith("AQ.")
    }
  }
}

internal interface ScreenTranslationPreferenceNode {
  fun get(key: String): String?

  fun put(key: String, value: String)
}

private class JavaScreenTranslationPreferenceNode(
    private val preferences: Preferences =
        Preferences.userNodeForPackage(ScreenTranslationPreferencesStore::class.java)
            .node("screen-translation"),
) : ScreenTranslationPreferenceNode {
  override fun get(key: String): String? = preferences.get(key, null)

  override fun put(key: String, value: String) {
    preferences.put(key, value)
  }
}

internal class ScreenTranslationPreferencesStore(
    private val node: ScreenTranslationPreferenceNode = JavaScreenTranslationPreferenceNode(),
) {
  fun load(): ScreenTranslationSettings =
      runCatching {
            val providerName = get(KEY_PROVIDER)
            val geminiApiKey = get(KEY_GEMINI_API_KEY).orEmpty()
            val geminiModel = get(KEY_GEMINI_MODEL).orEmpty()
            val openAiApiKey = get(KEY_OPENAI_API_KEY).orEmpty()
            val openAiModel = get(KEY_OPENAI_MODEL).orEmpty()
            val ollamaBaseUrl = get(KEY_OLLAMA_BASE_URL).orEmpty()
            val ollamaModel = get(KEY_OLLAMA_MODEL).orEmpty()
            val apiKey = get(KEY_API_KEY).orEmpty()
            val baseUrl = get(KEY_BASE_URL).orEmpty()
            val model = get(KEY_MODEL).orEmpty()
            val provider =
                ScreenTranslationProvider.entries.firstOrNull { it.name == providerName }
                    ?: inferProvider(apiKey, baseUrl, model)
            ScreenTranslationSettings(
                    provider = provider,
                    geminiApiKey = geminiApiKey,
                    geminiModel = geminiModel,
                    openAiApiKey = openAiApiKey,
                    openAiModel = openAiModel,
                    ollamaBaseUrl = ollamaBaseUrl,
                    ollamaModel = ollamaModel,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    model = model,
                    targetLanguage =
                        get(KEY_TARGET_LANGUAGE)
                            ?: ScreenTranslationSettings.DEFAULT_TARGET_LANGUAGE,
                    pauseWhileTranslating = get(KEY_PAUSE_WHILE_TRANSLATING) == "true",
                )
                .sanitized()
          }
          .getOrDefault(ScreenTranslationSettings())

  fun save(settings: ScreenTranslationSettings) {
    val clean = settings.sanitized()
    runCatching {
      put(KEY_PROVIDER, clean.provider.name)
      put(KEY_GEMINI_API_KEY, clean.geminiApiKey)
      put(KEY_GEMINI_MODEL, clean.geminiModel)
      put(KEY_OPENAI_API_KEY, clean.openAiApiKey)
      put(KEY_OPENAI_MODEL, clean.openAiModel)
      put(KEY_OLLAMA_BASE_URL, clean.ollamaBaseUrl)
      put(KEY_OLLAMA_MODEL, clean.ollamaModel)
      put(KEY_API_KEY, clean.apiKey)
      put(KEY_BASE_URL, clean.baseUrl)
      put(KEY_MODEL, clean.model)
      put(KEY_TARGET_LANGUAGE, clean.targetLanguage)
      put(KEY_PAUSE_WHILE_TRANSLATING, clean.pauseWhileTranslating.toString())
    }
  }

  private fun inferProvider(
      apiKey: String,
      baseUrl: String,
      model: String,
  ): ScreenTranslationProvider =
      when {
        ScreenTranslationSettings.isOllamaEndpoint(baseUrl) ||
            ScreenTranslationSettings.isOllamaModel(model) -> ScreenTranslationProvider.OLLAMA
        ScreenTranslationSettings.isGeminiKeyPrefix(apiKey) -> ScreenTranslationProvider.GEMINI
        apiKey.isNotBlank() -> ScreenTranslationProvider.OPENAI_COMPATIBLE
        baseUrl.contains("openai", ignoreCase = true) ->
            ScreenTranslationProvider.OPENAI_COMPATIBLE
        else -> ScreenTranslationProvider.GEMINI
      }

  private fun get(key: String): String? {
    require(key in ALLOWED_KEYS) { "Disallowed screen translation preference key: $key" }
    return node.get(key)
  }

  private fun put(key: String, value: String) {
    require(key in ALLOWED_KEYS) { "Disallowed screen translation preference key: $key" }
    node.put(key, value)
  }

  companion object {
    internal const val KEY_PROVIDER = "provider"
    internal const val KEY_GEMINI_API_KEY = "gemini-api-key"
    internal const val KEY_GEMINI_MODEL = "gemini-model"
    internal const val KEY_OPENAI_API_KEY = "openai-api-key"
    internal const val KEY_OPENAI_MODEL = "openai-model"
    internal const val KEY_OLLAMA_BASE_URL = "ollama-base-url"
    internal const val KEY_OLLAMA_MODEL = "ollama-model"
    internal const val KEY_API_KEY = "api-key"
    internal const val KEY_BASE_URL = "base-url"
    internal const val KEY_MODEL = "model"
    internal const val KEY_TARGET_LANGUAGE = "target-language"
    internal const val KEY_PAUSE_WHILE_TRANSLATING = "pause-while-translating"

    private val ALLOWED_KEYS =
        setOf(
            KEY_PROVIDER,
            KEY_GEMINI_API_KEY,
            KEY_GEMINI_MODEL,
            KEY_OPENAI_API_KEY,
            KEY_OPENAI_MODEL,
            KEY_OLLAMA_BASE_URL,
            KEY_OLLAMA_MODEL,
            KEY_API_KEY,
            KEY_BASE_URL,
            KEY_MODEL,
            KEY_TARGET_LANGUAGE,
            KEY_PAUSE_WHILE_TRANSLATING,
        )
  }
}
