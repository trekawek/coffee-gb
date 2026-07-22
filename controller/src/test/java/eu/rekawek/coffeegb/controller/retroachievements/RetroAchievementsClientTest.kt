package eu.rekawek.coffeegb.controller.retroachievements

import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.memory.cart.Rom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class RetroAchievementsClientTest {

  @Test
  fun usesOfficialApiDataToMatchRomAndPublishProgress() {
    val rom = rom()
    val hash = retroAchievementsMd5(rom.sourceData)
    val api =
        FakeApi(
            games =
                listOf(
                    RetroApiGame(
                        42,
                        "API title",
                        listOf(hash.uppercase()),
                    )),
            progress =
                RetroApiGameProgress(
                    "Test Game",
                    listOf(
                        RetroApiAchievement(2, "Second", "Later", 10, false, 2),
                        RetroApiAchievement(1, "First", "Earlier", 5, true, 1),
                    ),
                ),
        )
    val store = InMemoryCredentialsStore()
    val eventBus = EventBusImpl(null, null, false)
    val statuses = LinkedBlockingQueue<RetroAchievements.StatusEvent>()
    val lists = LinkedBlockingQueue<RetroAchievements.AchievementListEvent>()
    eventBus.register<RetroAchievements.StatusEvent> { statuses.add(it) }
    eventBus.register<RetroAchievements.AchievementListEvent> { lists.add(it) }
    val client =
        RetroAchievementsClient(
            eventBus,
            EmulatorProperties(),
            apiFactory = { username, apiKey ->
              assertEquals("tester", username)
              assertEquals("secret", apiKey)
              api
            },
            credentialsStore = store,
        )

    try {
      val key = "secret".toCharArray()
      client.login(" tester ", key)
      assertTrue(key.all { it == '\u0000' })
      assertNotNull(awaitStatus(statuses) { it.loggedIn })
      assertEquals(RetroAchievementsCredentials("tester", "secret"), store.credentials)

      client.attach(rom)
      val loaded = assertNotNull(awaitStatus(statuses) { it.gameTitle == "Test Game" })
      assertEquals(1, loaded.unlockedAchievements)
      assertEquals(2, loaded.totalAchievements)
      assertEquals(4L, api.lastConsoleId)

      client.publishAchievements()
      val list = assertNotNull(lists.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertContentEquals(listOf(1, 2), list.achievements.map { it.id })
      assertTrue(list.achievements.first().unlocked)
    } finally {
      client.close()
      eventBus.close()
    }
  }

  @Test
  fun ignoresAnIdentificationResponseAfterAnotherRomIsLoaded() {
    val first = rom(1)
    val second = rom(2)
    val releaseFirstRequest = CountDownLatch(1)
    val firstRequestStarted = CountDownLatch(1)
    val api =
        FakeApi(
            games =
                listOf(
                    RetroApiGame(1, "First", listOf(retroAchievementsMd5(first.sourceData))),
                    RetroApiGame(2, "Second", listOf(retroAchievementsMd5(second.sourceData))),
                ),
            progress = RetroApiGameProgress("Second", emptyList()),
            firstRequestStarted = firstRequestStarted,
            releaseFirstRequest = releaseFirstRequest,
        )
    val eventBus = EventBusImpl(null, null, false)
    val statuses = LinkedBlockingQueue<RetroAchievements.StatusEvent>()
    eventBus.register<RetroAchievements.StatusEvent> { statuses.add(it) }
    val client =
        RetroAchievementsClient(
            eventBus,
            EmulatorProperties(),
            apiFactory = { _, _ -> api },
            credentialsStore = InMemoryCredentialsStore(),
        )

    try {
      client.login("tester", "secret".toCharArray())
      assertNotNull(awaitStatus(statuses) { it.loggedIn })
      client.attach(first)
      assertTrue(firstRequestStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      client.attach(second)
      releaseFirstRequest.countDown()

      assertNotNull(awaitStatus(statuses) { it.gameTitle == "Second" })
      assertContentEquals(listOf(2L), api.requestedGameIds)
    } finally {
      releaseFirstRequest.countDown()
      client.close()
      eventBus.close()
    }
  }

  @Test
  fun calculatesTheRetroAchievementsMd5() {
    assertEquals("900150983cd24fb0d6963f7d28e17f72", retroAchievementsMd5("abc".toByteArray()))
  }

  private fun awaitStatus(
      statuses: LinkedBlockingQueue<RetroAchievements.StatusEvent>,
      predicate: (RetroAchievements.StatusEvent) -> Boolean,
  ): RetroAchievements.StatusEvent? {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      val status = statuses.poll(100, TimeUnit.MILLISECONDS) ?: continue
      if (predicate(status)) return status
    }
    return null
  }

  private fun rom(marker: Byte = 0): Rom {
    val data = ByteArray(0x8000)
    data[0] = marker
    data[0x0147] = 0
    data[0x0148] = 0
    data[0x0149] = 0
    return Rom(data)
  }

  private class FakeApi(
      private val games: List<RetroApiGame>,
      private val progress: RetroApiGameProgress,
      private val firstRequestStarted: CountDownLatch? = null,
      private val releaseFirstRequest: CountDownLatch? = null,
  ) : RetroAchievementsApi {
    @Volatile var lastConsoleId: Long? = null
    val requestedGameIds = mutableListOf<Long>()

    override fun getUser(username: String) = RetroApiResult.Success(username)

    override fun getGames(consoleId: Long): RetroApiResult<List<RetroApiGame>> {
      lastConsoleId = consoleId
      firstRequestStarted?.countDown()
      releaseFirstRequest?.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      return RetroApiResult.Success(games)
    }

    override fun getGameProgress(
        username: String,
        gameId: Long,
    ): RetroApiResult<RetroApiGameProgress> {
      requestedGameIds += gameId
      return RetroApiResult.Success(progress)
    }
  }

  private class InMemoryCredentialsStore : RetroAchievementsCredentialsStore {
    var credentials: RetroAchievementsCredentials? = null

    override fun load() = credentials

    override fun save(credentials: RetroAchievementsCredentials) {
      this.credentials = credentials
    }

    override fun clear() {
      credentials = null
    }
  }

  private companion object {
    const val TIMEOUT_SECONDS = 5L
  }
}
