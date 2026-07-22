package eu.rekawek.coffeegb.controller.retroachievements

import com.haroldadmin.cnradapter.NetworkResponse
import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.memory.cart.Rom
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.retroachivements.api.RetroClient
import org.retroachivements.api.core.RequiresCache
import org.retroachivements.api.data.RetroCredentials
import org.retroachivements.api.data.pojo.ErrorResponse
import org.slf4j.LoggerFactory

/**
 * Connects Coffee GB to the RetroAchievements Web API. API calls run away from the emulation
 * thread; stale responses are discarded when the user signs out or loads another ROM.
 */
internal class RetroAchievementsClient(
    private val eventBus: EventBus,
    properties: EmulatorProperties,
    private val apiFactory: (String, String) -> RetroAchievementsApi =
        { username, apiKey -> OfficialRetroAchievementsApi(username, apiKey) },
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor {
          Thread(it, "retroachievements-api").apply { isDaemon = true }
        },
    private val credentialsStore: RetroAchievementsCredentialsStore =
        EmulatorPropertiesRetroAchievementsCredentialsStore(properties),
) : AutoCloseable {

  private val authGeneration = AtomicInteger()
  private val loadGeneration = AtomicInteger()
  private val gamesByConsole = ConcurrentHashMap<Long, List<RetroApiGame>>()

  @Volatile private var api: RetroAchievementsApi? = null
  @Volatile private var username: String? = null
  @Volatile private var pendingRom: Rom? = null
  @Volatile private var gameTitle: String? = null
  @Volatile private var achievements = emptyList<RetroAchievements.Achievement>()
  @Volatile private var statusMessage: String? = "Not connected"
  @Volatile private var closed = false

  init {
    credentialsStore.load()?.let {
      authenticate(it.username, it.apiKey.toCharArray(), restoring = true)
    }
  }

  val isAvailable: Boolean
    get() = !closed

  fun login(username: String, apiKey: CharArray) {
    authenticate(username, apiKey, restoring = false)
  }

  private fun authenticate(username: String, apiKey: CharArray, restoring: Boolean) {
    if (closed) {
      apiKey.fill('\u0000')
      return
    }
    val cleanUsername = username.trim()
    if (cleanUsername.isEmpty() || apiKey.isEmpty()) {
      statusMessage = "Username and Web API key are required"
      publishStatus()
      apiKey.fill('\u0000')
      return
    }

    val key = apiKey.concatToString()
    apiKey.fill('\u0000')
    val generation = authGeneration.incrementAndGet()
    loadGeneration.incrementAndGet()
    api = null
    this.username = null
    gamesByConsole.clear()
    clearGame()
    statusMessage = if (restoring) "Restoring connection…" else "Connecting…"
    publishStatus()

    executor.execute {
      val candidate = runCatching { apiFactory(cleanUsername, key) }
      val result =
          candidate.fold(
              onSuccess = { it.getUser(cleanUsername) },
              onFailure = {
                LOG.warn("Can't create RetroAchievements API client", it)
                RetroApiResult.Failure(readableRetroAchievementsMessage(it))
              },
          )
      if (closed || generation != authGeneration.get()) return@execute

      when (result) {
        is RetroApiResult.Success -> {
          val canonicalUsername = result.value
          api = candidate.getOrNull()
          this.username = canonicalUsername
          credentialsStore.save(RetroAchievementsCredentials(canonicalUsername, key))
          statusMessage = "Connected as $canonicalUsername"
          eventBus.post(Controller.NotificationEvent(statusMessage!!, NOTIFICATION_DURATION_MS))
          publishStatus()
          pendingRom?.let(::beginLoadGame)
        }
        is RetroApiResult.Failure -> {
          statusMessage = result.message
          if (restoring) {
            credentialsStore.clear()
          }
          publishStatus()
        }
      }
    }
  }

  fun logout() {
    authGeneration.incrementAndGet()
    loadGeneration.incrementAndGet()
    api = null
    username = null
    gamesByConsole.clear()
    clearGame()
    statusMessage = "Not connected"
    credentialsStore.clear()
    publishStatus()
  }

  fun attach(rom: Rom) {
    pendingRom = rom
    clearGame()
    val currentApi = api
    val currentUsername = username
    if (currentApi != null && currentUsername != null) {
      beginLoadGame(rom)
    } else {
      publishStatus()
    }
  }

  fun detach() {
    loadGeneration.incrementAndGet()
    pendingRom = null
    clearGame()
    statusMessage = if (username == null) "Not connected" else "Connected as $username"
    publishStatus()
  }

  /** A reset keeps the same read-only game progress; only the active ROM reference changes. */
  fun reset(rom: Rom) {
    pendingRom = rom
  }

  fun publishStatus() {
    val currentAchievements = achievements
    eventBus.post(
        RetroAchievements.StatusEvent(
            isAvailable,
            username != null,
            username,
            gameTitle,
            currentAchievements.count { it.unlocked },
            currentAchievements.size,
            statusMessage,
        ))
  }

  fun publishAchievements() {
    val title = gameTitle
    if (title == null) {
      eventBus.post(
          RetroAchievements.AchievementListEvent(
              null,
              emptyList(),
              if (username == null) {
                "Connect and load a supported game first"
              } else {
                "No supported game is loaded"
              },
          ))
      return
    }
    eventBus.post(RetroAchievements.AchievementListEvent(title, achievements))
  }

  private fun beginLoadGame(rom: Rom) {
    val currentApi = api ?: return
    val currentUsername = username ?: return
    val generation = loadGeneration.incrementAndGet()
    val consoleId =
        if (rom.gameboyColorFlag == Rom.GameboyColorFlag.NON_CGB) CONSOLE_GAME_BOY
        else CONSOLE_GAME_BOY_COLOR
    val hash = retroAchievementsMd5(rom.sourceData)
    clearGame()
    statusMessage = "Identifying game…"
    publishStatus()

    executor.execute {
      val gamesResult =
          gamesByConsole[consoleId]?.let { RetroApiResult.Success(it) }
              ?: currentApi.getGames(consoleId).also {
                if (it is RetroApiResult.Success) gamesByConsole[consoleId] = it.value
              }
      if (!isCurrentLoad(generation, rom)) return@execute
      val games =
          when (gamesResult) {
            is RetroApiResult.Success -> gamesResult.value
            is RetroApiResult.Failure -> {
              statusMessage = gamesResult.message
              publishStatus()
              return@execute
            }
          }
      val game = games.firstOrNull { candidate -> candidate.hashes.any { it.equals(hash, true) } }
      if (game == null) {
        statusMessage = "No RetroAchievements set found for this ROM"
        publishStatus()
        return@execute
      }

      when (val progress = currentApi.getGameProgress(currentUsername, game.id)) {
        is RetroApiResult.Success -> {
          if (!isCurrentLoad(generation, rom)) return@execute
          gameTitle = progress.value.title
          achievements =
              progress.value.achievements
                  .sortedWith(compareBy<RetroApiAchievement> { it.displayOrder }.thenBy { it.id })
                  .map {
                    RetroAchievements.Achievement(
                        it.id,
                        it.title,
                        it.description,
                        it.points,
                        it.unlocked,
                    )
                  }
          val unlocked = achievements.count { it.unlocked }
          statusMessage = "$gameTitle: $unlocked of ${achievements.size} achievements unlocked"
          eventBus.post(Controller.NotificationEvent(statusMessage!!, NOTIFICATION_DURATION_MS))
          publishStatus()
        }
        is RetroApiResult.Failure -> {
          if (!isCurrentLoad(generation, rom)) return@execute
          statusMessage = progress.message
          publishStatus()
        }
      }
    }
  }

  private fun isCurrentLoad(generation: Int, rom: Rom): Boolean =
      !closed && generation == loadGeneration.get() && pendingRom === rom

  private fun clearGame() {
    gameTitle = null
    achievements = emptyList()
  }

  override fun close() {
    closed = true
    authGeneration.incrementAndGet()
    loadGeneration.incrementAndGet()
    api = null
    pendingRom = null
    executor.shutdownNow()
  }

  private companion object {
    val LOG = LoggerFactory.getLogger(RetroAchievementsClient::class.java)
    const val NOTIFICATION_DURATION_MS = 5000
    const val CONSOLE_GAME_BOY = 4L
    const val CONSOLE_GAME_BOY_COLOR = 6L

  }
}

internal interface RetroAchievementsApi {
  fun getUser(username: String): RetroApiResult<String>

  fun getGames(consoleId: Long): RetroApiResult<List<RetroApiGame>>

  fun getGameProgress(username: String, gameId: Long): RetroApiResult<RetroApiGameProgress>
}

internal class OfficialRetroAchievementsApi(username: String, apiKey: String) :
    RetroAchievementsApi {
  private val api = RetroClient(RetroCredentials(username, apiKey)).api

  override fun getUser(username: String): RetroApiResult<String> =
      request { api.getUserProfile(username) }.map { it.user }

  // The controller caches this large response once per console for the application lifetime.
  @OptIn(RequiresCache::class)
  override fun getGames(consoleId: Long): RetroApiResult<List<RetroApiGame>> =
      request { api.getGameList(consoleId, 1, 1) }.map { response ->
        response.map { RetroApiGame(it.id, it.title, it.hashes) }
      }

  override fun getGameProgress(
      username: String,
      gameId: Long,
  ): RetroApiResult<RetroApiGameProgress> =
      request { api.getGameInfoAndUserProgress(username, gameId) }.map { response ->
        RetroApiGameProgress(
            response.title,
            response.achievements.mapNotNull { (key, achievement) ->
              val id = achievement.id.toIntOrNull() ?: key.toIntOrNull() ?: return@mapNotNull null
              RetroApiAchievement(
                  id,
                  achievement.title,
                  achievement.description,
                  achievement.points.toInt(),
                  achievement.dateEarned != null || achievement.dateEarnedHardcore != null,
                  achievement.displayOrder,
              )
            },
        )
      }

  private fun <T> request(block: suspend () -> NetworkResponse<T, ErrorResponse>): RetroApiResult<T> =
      try {
        when (val response = await(block)) {
          is NetworkResponse.Success -> RetroApiResult.Success(response.body)
          is NetworkResponse.ServerError ->
              RetroApiResult.Failure(
                  response.body?.message?.takeIf { it.isNotBlank() }
                      ?: "RetroAchievements returned HTTP ${response.code ?: "error"}")
          is NetworkResponse.NetworkError ->
              RetroApiResult.Failure(
                  response.error.message?.takeIf { it.isNotBlank() }
                      ?: "Can't reach RetroAchievements")
          is NetworkResponse.UnknownError ->
              RetroApiResult.Failure(readableRetroAchievementsMessage(response.error))
        }
      } catch (e: Exception) {
        RetroApiResult.Failure(readableRetroAchievementsMessage(e))
      }

  private fun <T> await(block: suspend () -> T): T {
    val completed = CountDownLatch(1)
    val resultRef = AtomicReference<Result<T>>()
    block.startCoroutine(
        object : Continuation<T> {
          override val context = EmptyCoroutineContext

          override fun resumeWith(result: Result<T>) {
            resultRef.set(result)
            completed.countDown()
          }
        })
    completed.await()
    return resultRef.get().getOrThrow()
  }
}

internal sealed interface RetroApiResult<out T> {
  data class Success<T>(val value: T) : RetroApiResult<T>

  data class Failure(val message: String) : RetroApiResult<Nothing>
}

internal inline fun <T, R> RetroApiResult<T>.map(transform: (T) -> R): RetroApiResult<R> =
    when (this) {
      is RetroApiResult.Success -> RetroApiResult.Success(transform(value))
      is RetroApiResult.Failure -> this
    }

internal data class RetroApiGame(val id: Long, val title: String, val hashes: List<String>)

internal data class RetroApiGameProgress(
    val title: String,
    val achievements: List<RetroApiAchievement>,
)

internal data class RetroApiAchievement(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    val unlocked: Boolean,
    val displayOrder: Int,
)

internal data class RetroAchievementsCredentials(val username: String, val apiKey: String)

internal interface RetroAchievementsCredentialsStore {
  fun load(): RetroAchievementsCredentials?

  fun save(credentials: RetroAchievementsCredentials)

  fun clear()
}

private class EmulatorPropertiesRetroAchievementsCredentialsStore(
    private val properties: EmulatorProperties,
) : RetroAchievementsCredentialsStore {
  override fun load(): RetroAchievementsCredentials? {
    val username =
        properties.getProperty(EmulatorProperties.Key.RetroAchievementsUsername, "")
            ?.takeIf { it.isNotBlank() }
            ?: return null
    val apiKey =
        properties.getProperty(EmulatorProperties.Key.RetroAchievementsApiKey, "")
            ?.takeIf { it.isNotBlank() }
            ?: return null
    return RetroAchievementsCredentials(username, apiKey)
  }

  override fun save(credentials: RetroAchievementsCredentials) {
    properties.setProperty(
        EmulatorProperties.Key.RetroAchievementsUsername,
        credentials.username,
    )
    properties.setProperty(EmulatorProperties.Key.RetroAchievementsApiKey, credentials.apiKey)
  }

  override fun clear() {
    properties.removeProperty(EmulatorProperties.Key.RetroAchievementsUsername)
    properties.removeProperty(EmulatorProperties.Key.RetroAchievementsApiKey)
  }
}

internal fun retroAchievementsMd5(bytes: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

private fun readableRetroAchievementsMessage(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
