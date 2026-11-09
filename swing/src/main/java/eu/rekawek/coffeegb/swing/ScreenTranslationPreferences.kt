package eu.rekawek.coffeegb.swing

import java.net.URI
import java.util.Locale
import java.util.prefs.Preferences

internal enum class ScreenTranslationProvider(
    val displayName: String,
    val requiresApiKey: Boolean,
    val supportsCustomBaseUrl: Boolean,
    val supportsModelSelection: Boolean,
) {
  GEMINI(
      displayName = "Google Gemini",
      requiresApiKey = true,
      supportsCustomBaseUrl = false,
      supportsModelSelection = true,
  ),
  OPENAI_COMPATIBLE(
      displayName = "OpenAI",
      requiresApiKey = true,
      supportsCustomBaseUrl = false,
      supportsModelSelection = true,
  ),
  OLLAMA(
      displayName = "Ollama (Local)",
      requiresApiKey = false,
      supportsCustomBaseUrl = true,
      supportsModelSelection = true,
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
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val targetLanguage: String = DEFAULT_TARGET_LANGUAGE,
    val pauseWhileTranslating: Boolean = false,
) {
  fun sanitized(): ScreenTranslationSettings =
      copy(
          apiKey = apiKey.trim().take(MAX_FIELD_LENGTH),
          baseUrl = baseUrl.trim().trimEnd('/').take(MAX_FIELD_LENGTH),
          model = model.trim().take(MAX_MODEL_LENGTH),
          targetLanguage =
              targetLanguage.trim().take(MAX_LANGUAGE_LENGTH).ifEmpty { DEFAULT_TARGET_LANGUAGE },
      )

  fun resolve(): ResolvedScreenTranslationConfig? {
    val clean = sanitized()
    val ignoreOllamaOverrides =
        clean.provider == ScreenTranslationProvider.GEMINI ||
            clean.provider == ScreenTranslationProvider.OPENAI_COMPATIBLE
    val effectiveBaseUrl =
        clean.baseUrl.takeUnless { ignoreOllamaOverrides && isOllamaEndpoint(it) }.orEmpty()
    val effectiveModel =
        clean.model.takeUnless { ignoreOllamaOverrides && isOllamaModel(it) }.orEmpty()

    val protocol =
        when (clean.provider) {
          ScreenTranslationProvider.GEMINI -> ResolvedTranslationProtocol.GEMINI
          ScreenTranslationProvider.OPENAI_COMPATIBLE,
          ScreenTranslationProvider.OLLAMA -> ResolvedTranslationProtocol.OPENAI_COMPATIBLE
        }

    val resolvedBaseUrl =
        when (clean.provider) {
          ScreenTranslationProvider.GEMINI ->
              effectiveBaseUrl.ifEmpty { DEFAULT_GEMINI_BASE_URL }
          ScreenTranslationProvider.OPENAI_COMPATIBLE ->
              effectiveBaseUrl.ifEmpty { DEFAULT_OPENAI_BASE_URL }
          ScreenTranslationProvider.OLLAMA ->
              clean.baseUrl.ifEmpty { DEFAULT_OLLAMA_BASE_URL }
        }

    val resolvedModel =
        when (clean.provider) {
          ScreenTranslationProvider.GEMINI ->
              effectiveModel.ifEmpty { DEFAULT_GEMINI_MODEL }
          ScreenTranslationProvider.OPENAI_COMPATIBLE ->
              effectiveModel.ifEmpty { DEFAULT_OPENAI_MODEL }
          ScreenTranslationProvider.OLLAMA ->
              clean.model.ifEmpty { DEFAULT_OLLAMA_MODEL }
        }

    val resolvedKey =
        if (clean.provider.requiresApiKey) {
          clean.apiKey
        } else {
          ""
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
            val apiKey = get(KEY_API_KEY).orEmpty()
            val baseUrl = get(KEY_BASE_URL).orEmpty()
            val model = get(KEY_MODEL).orEmpty()
            val provider =
                ScreenTranslationProvider.entries.firstOrNull { it.name == providerName }
                    ?: inferProvider(apiKey, baseUrl, model)
            ScreenTranslationSettings(
                    provider = provider,
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
        apiKey.trim().startsWith("AIza") -> ScreenTranslationProvider.GEMINI
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
    internal const val KEY_API_KEY = "api-key"
    internal const val KEY_BASE_URL = "base-url"
    internal const val KEY_MODEL = "model"
    internal const val KEY_TARGET_LANGUAGE = "target-language"
    internal const val KEY_PAUSE_WHILE_TRANSLATING = "pause-while-translating"

    private val ALLOWED_KEYS =
        setOf(
            KEY_PROVIDER,
            KEY_API_KEY,
            KEY_BASE_URL,
            KEY_MODEL,
            KEY_TARGET_LANGUAGE,
            KEY_PAUSE_WHILE_TRANSLATING,
        )
  }
}
