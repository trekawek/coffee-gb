package eu.rekawek.coffeegb.controller.retroachievements

import com.sun.jna.Pointer
import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.memory.cart.Rom
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory

/**
 * Owns the official rcheevos client for a single-player controller. HTTP completions arrive on
 * HttpClient workers, are queued, and are delivered to the native runtime by the emulation
 * thread so memory validation cannot race CPU execution.
 */
internal class RetroAchievementsClient(
    private val eventBus: EventBus,
    private val properties: EmulatorProperties,
    private val httpClient: HttpClient =
        HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build(),
    nativeFactory: () -> RetroAchievementsNative = { RetroAchievementsNative.load() },
) : AutoCloseable {

  private val nativeLock = Any()
  private val requestIds = AtomicInteger()
  private val operations = ConcurrentHashMap<Int, Operation>()
  private val serverResponses = ConcurrentLinkedQueue<ServerResponse>()

  @Volatile private var gameboy: Gameboy? = null
  @Volatile private var pendingRom: Rom? = null
  @Volatile private var username: String? = null
  @Volatile private var gameTitle: String? = null
  @Volatile private var unlockedAchievements = 0
  @Volatile private var totalAchievements = 0
  @Volatile private var statusMessage: String? = null
  @Volatile private var closed = false
  @Volatile private var activeLoginRequestId = 0
  @Volatile private var activeLoadRequestId = 0

  private val native: RetroAchievementsNative?
  private var client: Pointer? = null

  // Native code retains these function pointers for the lifetime of the client.
  private val readMemoryCallback =
      RetroAchievementsNative.ReadMemoryCallback { address, buffer, numBytes, _ ->
        readMemory(address, buffer, numBytes)
      }
  private val serverCallCallback =
      RetroAchievementsNative.ServerCallCallback { url, postData, contentType, context, _ ->
        sendServerCall(url, postData, contentType, context)
      }
  private val operationCallback =
      RetroAchievementsNative.OperationCallback {
          requestId,
          result,
          error,
          responseUsername,
          _,
          token,
          gameId,
          responseGameTitle,
          unlocked,
          total,
          _ ->
        onOperationComplete(
            requestId,
            result,
            error,
            responseUsername,
            token,
            gameId,
            responseGameTitle,
            unlocked,
            total,
        )
      }
  private val eventCallback =
      RetroAchievementsNative.EventCallback { type, id, title, description, detail, value, _ ->
        onRuntimeEvent(type, id, title, description, detail, value)
      }

  init {
    var loadedNative: RetroAchievementsNative? = null
    try {
      loadedNative = nativeFactory()
      client =
          loadedNative.coffee_ra_create(
              readMemoryCallback,
              serverCallCallback,
              operationCallback,
              eventCallback,
              null,
          ) ?: throw IllegalStateException("rcheevos could not create a client")
      statusMessage = "Not signed in"
    } catch (e: Throwable) {
      LOG.warn("RetroAchievements is unavailable", e)
      statusMessage = "Native runtime unavailable on this platform"
    }
    native = loadedNative

    val savedUsername =
        properties.getProperty(EmulatorProperties.Key.RetroAchievementsUsername, "")
            ?.takeIf { it.isNotBlank() }
    val savedToken =
        properties.getProperty(EmulatorProperties.Key.RetroAchievementsToken, "")
            ?.takeIf { it.isNotBlank() }
    if (savedUsername != null && savedToken != null && isAvailable) {
      loginWithToken(savedUsername, savedToken)
    }
  }

  val isAvailable: Boolean
    get() = native != null && client != null && !closed

  fun login(username: String, password: CharArray) {
    if (!isAvailable) {
      publishStatus()
      password.fill('\u0000')
      return
    }
    val cleanUsername = username.trim()
    if (cleanUsername.isEmpty() || password.isEmpty()) {
      statusMessage = "Username and password are required"
      publishStatus()
      password.fill('\u0000')
      return
    }

    val requestId = beginOperation(Operation.LOGIN_PASSWORD)
    activeLoginRequestId = requestId
    statusMessage = "Signing in…"
    publishStatus()
    try {
      val passwordString = password.concatToString()
      synchronized(nativeLock) {
        native?.coffee_ra_begin_login_password(client ?: return, cleanUsername, passwordString, requestId)
      }
    } finally {
      password.fill('\u0000')
    }
  }

  private fun loginWithToken(savedUsername: String, token: String) {
    val requestId = beginOperation(Operation.LOGIN_TOKEN)
    activeLoginRequestId = requestId
    statusMessage = "Restoring sign-in…"
    synchronized(nativeLock) {
      native?.coffee_ra_begin_login_token(client ?: return, savedUsername, token, requestId)
    }
  }

  fun logout() {
    activeLoginRequestId = 0
    activeLoadRequestId = 0
    operations.clear()
    synchronized(nativeLock) {
      client?.let { native?.coffee_ra_logout(it) }
    }
    username = null
    gameTitle = null
    unlockedAchievements = 0
    totalAchievements = 0
    statusMessage = "Not signed in"
    properties.removeProperty(EmulatorProperties.Key.RetroAchievementsUsername)
    properties.removeProperty(EmulatorProperties.Key.RetroAchievementsToken)
    publishStatus()
  }

  fun attach(gameboy: Gameboy, rom: Rom) {
    this.gameboy = gameboy
    pendingRom = rom
    if (username != null) {
      beginLoadGame(rom)
    } else {
      publishStatus()
    }
  }

  fun detach() {
    activeLoadRequestId = 0
    synchronized(nativeLock) {
      client?.let { native?.coffee_ra_unload_game(it) }
    }
    gameboy = null
    pendingRom = null
    gameTitle = null
    unlockedAchievements = 0
    totalAchievements = 0
    publishStatus()
  }

  fun reset(gameboy: Gameboy, rom: Rom) {
    this.gameboy = gameboy
    pendingRom = rom
    synchronized(nativeLock) { client?.let { native?.coffee_ra_reset(it) } }
  }

  fun doFrame() {
    synchronized(nativeLock) {
      drainServerResponses()
      val handle = client ?: return
      val runtime = native ?: return
      if (runtime.coffee_ra_is_game_loaded(handle) != 0) {
        runtime.coffee_ra_do_frame(handle)
      } else {
        runtime.coffee_ra_idle(handle)
      }
    }
  }

  fun idle() {
    synchronized(nativeLock) {
      drainServerResponses()
      client?.let { native?.coffee_ra_idle(it) }
    }
  }

  fun saveProgress(): ByteArray? {
    synchronized(nativeLock) {
      val handle = client ?: return null
      val runtime = native ?: return null
      if (runtime.coffee_ra_is_game_loaded(handle) == 0) return null
      val size = runtime.coffee_ra_progress_size(handle)
      if (size <= 0 || size > MAX_PROGRESS_SIZE) return null
      val buffer = ByteArray(size.toInt())
      return if (runtime.coffee_ra_serialize_progress(handle, buffer, size) == RC_OK) buffer else null
    }
  }

  fun restoreProgress(progress: ByteArray?) {
    synchronized(nativeLock) {
      val handle = client ?: return
      val runtime = native ?: return
      if (progress == null || runtime.coffee_ra_is_game_loaded(handle) == 0) {
        runtime.coffee_ra_reset(handle)
      } else if (
          runtime.coffee_ra_deserialize_progress(handle, progress, progress.size.toLong()) != RC_OK) {
        runtime.coffee_ra_reset(handle)
      }
    }
  }

  fun publishStatus() {
    eventBus.post(
        RetroAchievements.StatusEvent(
            isAvailable,
            username != null,
            username,
            gameTitle,
            unlockedAchievements,
            totalAchievements,
            statusMessage,
        ))
  }

  fun publishAchievements() {
    val handle = client
    val runtime = native
    if (handle == null || runtime == null || gameTitle == null) {
      eventBus.post(
          RetroAchievements.AchievementListEvent(
              gameTitle,
              emptyList(),
              if (username == null) "Sign in and load a supported game first" else "No supported game is loaded",
          ))
      return
    }

    val achievements = mutableListOf<RetroAchievements.Achievement>()
    val callback =
        RetroAchievementsNative.AchievementCallback {
            id,
            title,
            description,
            progress,
            points,
            _,
            unlocked,
            _ ->
          achievements +=
              RetroAchievements.Achievement(
                  id,
                  title.orEmpty(),
                  description.orEmpty(),
                  progress.orEmpty(),
                  points,
                  unlocked.toInt() != 0,
              )
        }
    synchronized(nativeLock) { runtime.coffee_ra_iterate_achievements(handle, callback, null) }
    eventBus.post(RetroAchievements.AchievementListEvent(gameTitle, achievements))
  }

  private fun beginLoadGame(rom: Rom) {
    val handle = client ?: return
    val runtime = native ?: return
    val data = rom.sourceData
    val consoleId =
        if (rom.gameboyColorFlag == Rom.GameboyColorFlag.NON_CGB) CONSOLE_GAME_BOY
        else CONSOLE_GAME_BOY_COLOR
    val requestId = beginOperation(Operation.LOAD_GAME)
    activeLoadRequestId = requestId
    statusMessage = "Identifying game…"
    publishStatus()
    synchronized(nativeLock) {
      runtime.coffee_ra_begin_load_game(
          handle,
          consoleId,
          rom.file?.absolutePath ?: rom.title,
          data,
          data.size.toLong(),
          requestId,
      )
    }
  }

  private fun beginOperation(operation: Operation): Int {
    val requestId = requestIds.incrementAndGet()
    operations[requestId] = operation
    return requestId
  }

  private fun onOperationComplete(
      requestId: Int,
      result: Int,
      error: String?,
      responseUsername: String?,
      token: String?,
      gameId: Int,
      responseGameTitle: String?,
      unlocked: Int,
      total: Int,
  ) {
    if (closed) return
    val operation = operations.remove(requestId) ?: return
    if (
        (operation == Operation.LOAD_GAME && requestId != activeLoadRequestId) ||
            (operation != Operation.LOAD_GAME && requestId != activeLoginRequestId)) {
      return
    }
    if (result != RC_OK) {
      if (operation == Operation.LOAD_GAME) activeLoadRequestId = 0 else activeLoginRequestId = 0
      statusMessage = error?.takeIf { it.isNotBlank() } ?: "RetroAchievements error $result"
      if (operation == Operation.LOGIN_TOKEN) {
        properties.removeProperty(EmulatorProperties.Key.RetroAchievementsToken)
      }
      publishStatus()
      return
    }

    when (operation) {
      Operation.LOGIN_PASSWORD,
      Operation.LOGIN_TOKEN -> {
        activeLoginRequestId = 0
        username = responseUsername
        if (responseUsername != null && token != null) {
          properties.setProperty(EmulatorProperties.Key.RetroAchievementsUsername, responseUsername)
          properties.setProperty(EmulatorProperties.Key.RetroAchievementsToken, token)
        }
        statusMessage = "Signed in as ${responseUsername ?: "RetroAchievements user"} (softcore)"
        eventBus.post(Controller.NotificationEvent(statusMessage!!, NOTIFICATION_DURATION_MS))
        pendingRom?.let(::beginLoadGame)
      }
      Operation.LOAD_GAME -> {
        activeLoadRequestId = 0
        gameTitle = responseGameTitle?.takeIf { gameId != 0 }
        unlockedAchievements = unlocked
        totalAchievements = total
        statusMessage =
            if (gameTitle == null) {
              "No RetroAchievements set found for this ROM"
            } else {
              "$gameTitle: $unlocked of $total achievements unlocked"
            }
        eventBus.post(Controller.NotificationEvent(statusMessage!!, NOTIFICATION_DURATION_MS))
      }
    }
    publishStatus()
  }

  private fun onRuntimeEvent(
      type: Int,
      id: Int,
      title: String?,
      description: String?,
      detail: String?,
      value: Int,
  ) {
    val message =
        when (type) {
          EVENT_ACHIEVEMENT_TRIGGERED -> {
            unlockedAchievements = (unlockedAchievements + 1).coerceAtMost(totalAchievements)
            "Achievement unlocked: ${title.orEmpty()} (+$value)"
          }
          EVENT_LEADERBOARD_STARTED -> "Leaderboard started: ${title.orEmpty()}"
          EVENT_LEADERBOARD_FAILED -> "Leaderboard attempt failed: ${title.orEmpty()}"
          EVENT_LEADERBOARD_SUBMITTED -> "Leaderboard submitted: ${title.orEmpty()}"
          EVENT_GAME_COMPLETED -> "All achievements completed!"
          EVENT_SERVER_ERROR -> "RetroAchievements error: ${description ?: title ?: id}"
          EVENT_DISCONNECTED -> "RetroAchievements disconnected; unlocks are queued"
          EVENT_RECONNECTED -> "RetroAchievements reconnected"
          else -> null
        }
    if (message != null) {
      eventBus.post(Controller.NotificationEvent(message, NOTIFICATION_DURATION_MS))
      publishStatus()
    }
    if (type == EVENT_ACHIEVEMENT_PROGRESS_UPDATE && !detail.isNullOrBlank()) {
      LOG.debug("Achievement {} progress: {}", id, detail)
    }
  }

  private fun readMemory(address: Int, buffer: Pointer, numBytes: Int): Int {
    val machine = gameboy ?: return 0
    var read = 0
    while (read < numBytes) {
      val value = machine.readMemoryForAchievements(address + read)
      if (value < 0) break
      buffer.setByte(read.toLong(), value.toByte())
      read++
    }
    return read
  }

  private fun sendServerCall(
      url: String,
      postData: String?,
      contentType: String?,
      context: Pointer,
  ) {
    val runtime = native ?: return
    val uri = runCatching { URI(url) }.getOrNull()
    if (uri == null || uri.scheme != "https" || !isRetroAchievementsHost(uri.host)) {
      completeServerCall(runtime, context, ByteArray(0), CLIENT_ERROR)
      return
    }

    try {
      val builder =
          HttpRequest.newBuilder(uri)
              .timeout(HTTP_TIMEOUT)
              .header("User-Agent", userAgent())
              .header("Accept", "application/json")
      if (postData == null) {
        builder.GET()
      } else {
        builder.header("Content-Type", contentType ?: "application/x-www-form-urlencoded")
        builder.POST(HttpRequest.BodyPublishers.ofString(postData))
      }

      httpClient
          .sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
          .whenComplete { response, failure ->
            val completed =
                if (failure != null || response == null) {
                  ServerResponse(context, ByteArray(0), RETRYABLE_CLIENT_ERROR)
                } else {
                  ServerResponse(context, response.body(), response.statusCode())
                }
            synchronized(nativeLock) {
              if (closed) {
                completeServerCall(runtime, completed.context, completed.body, completed.status)
              } else {
                serverResponses.add(completed)
              }
            }
          }
    } catch (e: Exception) {
      LOG.warn("Can't send RetroAchievements request", e)
      completeServerCall(runtime, context, ByteArray(0), CLIENT_ERROR)
    }
  }

  private fun completeServerCall(
      runtime: RetroAchievementsNative,
      context: Pointer,
      body: ByteArray,
      status: Int,
  ) {
    synchronized(nativeLock) {
      runtime.coffee_ra_complete_server_call(context, body, body.size.toLong(), status)
    }
  }

  /** Must be called while holding [nativeLock], from the emulation thread. */
  private fun drainServerResponses() {
    val runtime = native ?: return
    while (true) {
      val response = serverResponses.poll() ?: return
      runtime.coffee_ra_complete_server_call(
          response.context,
          response.body,
          response.body.size.toLong(),
          response.status,
      )
    }
  }

  private fun userAgent(): String {
    val handle = client ?: return "coffee-gb/0.0.0"
    val runtime = native ?: return "coffee-gb/0.0.0"
    val buffer = ByteArray(128)
    val clause =
        synchronized(nativeLock) {
          runtime.coffee_ra_get_user_agent(handle, buffer, buffer.size.toLong())
        }
    val runtimeClause =
        if (clause > 0) buffer.decodeToString(0, buffer.indexOf(0).let { if (it < 0) buffer.size else it })
        else ""
    val version =
        javaClass.`package`.implementationVersion
            ?.substringBefore('-')
            ?.takeIf { it.matches(Regex("[0-9]+(?:\\.[0-9]+)*")) }
            ?: "0.0.0"
    return "coffee-gb/$version (${System.getProperty("os.name")}) $runtimeClause".trim()
  }

  override fun close() {
    synchronized(nativeLock) {
      closed = true
      drainServerResponses()
      val handle = client
      client = null
      if (handle != null) native?.coffee_ra_destroy(handle)
    }
    gameboy = null
    pendingRom = null
    operations.clear()
  }

  private enum class Operation {
    LOGIN_PASSWORD,
    LOGIN_TOKEN,
    LOAD_GAME,
  }

  private data class ServerResponse(
      val context: Pointer,
      val body: ByteArray,
      val status: Int,
  )

  private companion object {
    val LOG = LoggerFactory.getLogger(RetroAchievementsClient::class.java)
    val HTTP_TIMEOUT: Duration = Duration.ofSeconds(30)
    const val MAX_PROGRESS_SIZE = 16L * 1024 * 1024
    const val NOTIFICATION_DURATION_MS = 5000
    const val RC_OK = 0
    const val CLIENT_ERROR = -1
    const val RETRYABLE_CLIENT_ERROR = -2
    const val CONSOLE_GAME_BOY = 4
    const val CONSOLE_GAME_BOY_COLOR = 6
    const val EVENT_ACHIEVEMENT_TRIGGERED = 1
    const val EVENT_LEADERBOARD_STARTED = 2
    const val EVENT_LEADERBOARD_FAILED = 3
    const val EVENT_LEADERBOARD_SUBMITTED = 4
    const val EVENT_ACHIEVEMENT_PROGRESS_UPDATE = 9
    const val EVENT_GAME_COMPLETED = 15
    const val EVENT_SERVER_ERROR = 16
    const val EVENT_DISCONNECTED = 17
    const val EVENT_RECONNECTED = 18

    fun isRetroAchievementsHost(host: String?): Boolean =
        host == "retroachievements.org" || host?.endsWith(".retroachievements.org") == true
  }
}
