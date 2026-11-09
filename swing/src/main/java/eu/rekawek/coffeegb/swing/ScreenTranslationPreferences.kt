package eu.rekawek.coffeegb.swing

import java.net.URI
import java.util.Locale
import java.util.prefs.Preferences

internal enum class ScreenTranslationProvider(val displayName: String) {
  AUTO("Auto (Gemini, OpenAI, or Local Ollama)"),
  GEMINI("Google Gemini API"),
  OPENAI_COMPATIBLE("OpenAI-Compatible Vision API"),
  OLLAMA("Ollama (Local — qwen2.5vl:7b)"),
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
    val provider: ScreenTranslationProvider = ScreenTranslationProvider.AUTO,
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

  fun resolve(env: (String) -> String? = System::getenv): ResolvedScreenTranslationConfig? {
    val clean = sanitized()
    val envTranslateKey = env("COFFEEGB_TRANSLATE_API_KEY")?.trim().orEmpty()
    val envGeminiKey =
        env("GEMINI_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }
            ?: env("GOOGLE_API_KEY")?.trim().orEmpty()
    val envOpenAiKey = env("OPENAI_API_KEY")?.trim().orEmpty()
    val envBaseUrl =
        env("COFFEEGB_TRANSLATE_BASE_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    val envModel = env("COFFEEGB_TRANSLATE_MODEL")?.trim()?.takeIf { it.isNotEmpty() }

    val protocol =
        when (clean.provider) {
          ScreenTranslationProvider.GEMINI -> ResolvedTranslationProtocol.GEMINI
          ScreenTranslationProvider.OPENAI_COMPATIBLE,
          ScreenTranslationProvider.OLLAMA -> ResolvedTranslationProtocol.OPENAI_COMPATIBLE
          ScreenTranslationProvider.AUTO -> {
            val effectiveUrl = clean.baseUrl.ifEmpty { envBaseUrl.orEmpty() }
            when {
              effectiveUrl.contains("generativelanguage.googleapis.com", ignoreCase = true) ->
                  ResolvedTranslationProtocol.GEMINI
              effectiveUrl.isNotEmpty() -> ResolvedTranslationProtocol.OPENAI_COMPATIBLE
              clean.apiKey.startsWith("AIza") -> ResolvedTranslationProtocol.GEMINI
              clean.apiKey.isNotEmpty() -> ResolvedTranslationProtocol.OPENAI_COMPATIBLE
              envTranslateKey.startsWith("AIza") -> ResolvedTranslationProtocol.GEMINI
              envTranslateKey.isNotEmpty() -> ResolvedTranslationProtocol.OPENAI_COMPATIBLE
              envGeminiKey.isNotEmpty() -> ResolvedTranslationProtocol.GEMINI
              envOpenAiKey.isNotEmpty() -> ResolvedTranslationProtocol.OPENAI_COMPATIBLE
              else -> ResolvedTranslationProtocol.GEMINI
            }
          }
        }

    val resolvedKey =
        when {
          clean.apiKey.isNotEmpty() -> clean.apiKey
          envTranslateKey.isNotEmpty() -> envTranslateKey
          protocol == ResolvedTranslationProtocol.GEMINI && envGeminiKey.isNotEmpty() ->
              envGeminiKey
          protocol == ResolvedTranslationProtocol.OPENAI_COMPATIBLE && envOpenAiKey.isNotEmpty() ->
              envOpenAiKey
          envGeminiKey.isNotEmpty() -> envGeminiKey
          envOpenAiKey.isNotEmpty() -> envOpenAiKey
          else -> ""
        }

    val resolvedBaseUrl =
        when {
          clean.baseUrl.isNotEmpty() -> clean.baseUrl
          !envBaseUrl.isNullOrEmpty() -> envBaseUrl
          clean.provider == ScreenTranslationProvider.OLLAMA -> DEFAULT_OLLAMA_BASE_URL
          protocol == ResolvedTranslationProtocol.GEMINI ->
              env("GEMINI_BASE_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
                  ?: DEFAULT_GEMINI_BASE_URL
          else ->
              env("OPENAI_BASE_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
                  ?: DEFAULT_OPENAI_BASE_URL
        }

    val resolvedModel =
        when {
          clean.model.isNotEmpty() -> clean.model
          !envModel.isNullOrEmpty() -> envModel
          clean.provider == ScreenTranslationProvider.OLLAMA || isLocalEndpoint(resolvedBaseUrl) ->
              DEFAULT_OLLAMA_MODEL
          protocol == ResolvedTranslationProtocol.GEMINI -> DEFAULT_GEMINI_MODEL
          else -> DEFAULT_OPENAI_MODEL
        }

    if (resolvedKey.isEmpty() && !isLocalEndpoint(resolvedBaseUrl)) {
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
    const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"
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
            val provider =
                ScreenTranslationProvider.entries.firstOrNull { it.name == providerName }
                    ?: ScreenTranslationProvider.AUTO
            ScreenTranslationSettings(
                    provider = provider,
                    apiKey = get(KEY_API_KEY).orEmpty(),
                    baseUrl = get(KEY_BASE_URL).orEmpty(),
                    model = get(KEY_MODEL).orEmpty(),
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
