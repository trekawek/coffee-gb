package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfiguration
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationError
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationSaveResult
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationStore
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterNetworkPolicy
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterPortMapping
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterTransport
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.BackendRequest
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.OfferResult
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.text.PlainDocument
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class MobileAdapterConfigurationCoordinatorTest {

  @Test
  fun `runtime authorization starts disabled and every policy edit revokes it before commit`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-coordinator")
    val persistence = BlockingOwnerOnlyWriter()
    val store = MobileAdapterConfigurationStore(directory.resolve("adapter.bin"), persistence)
    val custom = customConfiguration()
    val coordinator = MobileAdapterConfigurationCoordinator(custom, store)
    val eventBus = EventBusImpl()
    try {
      val events = Collections.synchronizedList(mutableListOf<String>())
      eventBus.register<Controller.CancelMobileAdapterNetworkEvent> { events += "cancel" }
      eventBus.register<Controller.RefreshMobileAdapterConfigurationEvent> {
        events += "refresh:${it.revision}"
      }

      coordinator.provider.load().also { prepared ->
        assertFalse(prepared.runtimeNetworkConsent)
        assertFalse(prepared.runtimePrivateLocalDevelopment)
        closePrepared(prepared)
      }

      assertTrue(
          coordinator.applyRuntimeAuthorization(
              coordinator.snapshot().revision,
              true,
              true,
              eventBus,
          ))
      coordinator.provider.load().also { prepared ->
        assertTrue(prepared.runtimeNetworkConsent)
        assertTrue(prepared.runtimePrivateLocalDevelopment)
        closePrepared(prepared)
      }
      events.clear()

      val saved = CountDownLatch(1)
      coordinator.savePolicy(
          coordinator.snapshot().revision,
          MobileAdapterNetworkPolicy.Offline,
          eventBus,
      ) { result ->
        assertTrue(result.saved)
        saved.countDown()
      }
      assertFalse(coordinator.snapshot().networkConsent)
      assertFalse(coordinator.snapshot().privateLocalDevelopment)
      assertTrue(persistence.started.await(5, TimeUnit.SECONDS))
      val revokedRevision = coordinator.snapshot().revision
      assertEquals(listOf("cancel", "refresh:$revokedRevision"), events.take(2))
      coordinator.provider.load().also { prepared ->
        assertFalse(prepared.runtimeNetworkConsent)
        assertFalse(prepared.runtimePrivateLocalDevelopment)
        closePrepared(prepared)
      }
      persistence.release.countDown()
      assertTrue(saved.await(5, TimeUnit.SECONDS))

      val offline = coordinator.provider.load()
      assertNull(offline.networkBackend)
      assertFalse(offline.runtimeNetworkConsent)
      assertTrue(events.first() == "cancel")
      assertTrue(events.any { it.startsWith("refresh:") })
    } finally {
      persistence.release.countDown()
      coordinator.close()
      eventBus.close()
    }
  }

  @Test
  fun `policy conversion is exact bounded and redacted`() {
    val configuration = customConfiguration()
    val custom = configuration.networkPolicy as MobileAdapterNetworkPolicy.CustomServer
    val policy = MobileAdapterConfigurationCoordinator.destinationPolicy(9, custom)

    assertEquals(9, policy.revision)
    assertEquals(2, policy.rules().size)
    assertEquals(setOf(80, 53), policy.rules().map { it.guestPort }.toSet())
    assertFalse(policy.toString().contains("service.example"))
    assertFalse(policy.toString().contains("127.0.0.1"))
    assertTrue(policy.toString().contains("rules=2"))
  }

  @Test
  fun `failed policy save retains old policy with runtime authorization revoked`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-coordinator-failure")
    val coordinator =
        MobileAdapterConfigurationCoordinator(
            customConfiguration(),
            MobileAdapterConfigurationStore(
                directory.resolve("adapter.bin"),
                FailOwnerOnlyWriter(),
            ),
        )
    val eventBus = EventBusImpl()
    try {
      val events = Collections.synchronizedList(mutableListOf<String>())
      eventBus.register<Controller.CancelMobileAdapterNetworkEvent> { events += "cancel" }
      eventBus.register<Controller.RefreshMobileAdapterConfigurationEvent> {
        events += "refresh:${it.revision}"
      }
      assertTrue(
          coordinator.applyRuntimeAuthorization(
              coordinator.snapshot().revision,
              true,
              true,
              eventBus,
          ))
      events.clear()

      val completed = CountDownLatch(1)
      var saved = true
      coordinator.savePolicy(
          coordinator.snapshot().revision,
          MobileAdapterNetworkPolicy.Offline,
          eventBus,
      ) { result ->
        saved = result.saved
        completed.countDown()
      }
      assertTrue(completed.await(5, TimeUnit.SECONDS))

      val retained = coordinator.snapshot()
      assertFalse(saved)
      assertTrue(retained.configuration.networkPolicy is MobileAdapterNetworkPolicy.CustomServer)
      assertFalse(retained.networkConsent)
      assertFalse(retained.privateLocalDevelopment)
      assertEquals(4, events.size)
      assertEquals("cancel", events.first())
      assertTrue(events[1].startsWith("refresh:"))
      assertEquals("cancel", events[2])
      assertEquals("refresh:${retained.revision}", events.last())
      coordinator.provider.load().also { prepared ->
        assertFalse(prepared.runtimeNetworkConsent)
        assertFalse(prepared.runtimePrivateLocalDevelopment)
        closePrepared(prepared)
      }
    } finally {
      coordinator.close()
      eventBus.close()
    }
  }

  @Test
  fun `overlapping saves reconcile runtime to the last durable policy after a later failure`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-coordinator-overlap")
    val persistence = BlockingThenFailOwnerOnlyWriter()
    val store = MobileAdapterConfigurationStore(directory.resolve("adapter.bin"), persistence)
    val coordinator = MobileAdapterConfigurationCoordinator(customConfiguration(), store)
    val eventBus = EventBusImpl()
    try {
      val completed = CountDownLatch(2)
      val results = Collections.synchronizedList(mutableListOf<Boolean>())
      coordinator.savePolicy(
          coordinator.snapshot().revision,
          MobileAdapterNetworkPolicy.Offline,
          eventBus,
      ) { result ->
        results += result.saved
        completed.countDown()
      }
      assertTrue(persistence.started.await(5, TimeUnit.SECONDS))

      // This edit is queued while the first durable commit is blocked, then fails after it.
      coordinator.savePolicy(
          coordinator.snapshot().revision,
          customConfiguration().networkPolicy,
          eventBus,
      ) { result ->
        results += result.saved
        completed.countDown()
      }
      persistence.release.countDown()
      assertTrue(completed.await(5, TimeUnit.SECONDS))

      assertEquals(listOf(true, false), results)
      assertEquals(MobileAdapterNetworkPolicy.Offline, store.current().networkPolicy)
      val reconciled = coordinator.snapshot()
      assertEquals(MobileAdapterNetworkPolicy.Offline, reconciled.configuration.networkPolicy)
      assertFalse(reconciled.networkConsent)
      assertFalse(reconciled.privateLocalDevelopment)
      val prepared = coordinator.provider.load()
      assertNull(prepared.networkBackend)
      assertFalse(prepared.runtimeNetworkConsent)
    } finally {
      persistence.release.countDown()
      coordinator.close()
      eventBus.close()
    }
  }

  @Test
  fun `stale dialog revision cannot authorize a policy changed by an in-flight save`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-coordinator-stale-consent")
    val persistence = BlockingOwnerOnlyWriter()
    val coordinator =
        MobileAdapterConfigurationCoordinator(
            customConfiguration(),
            MobileAdapterConfigurationStore(directory.resolve("adapter.bin"), persistence),
        )
    val eventBus = EventBusImpl()
    try {
      val displayedRevision = coordinator.snapshot().revision
      val refreshes = Collections.synchronizedList(mutableListOf<Long>())
      eventBus.register<Controller.RefreshMobileAdapterConfigurationEvent> {
        refreshes += it.revision
      }
      val completed = CountDownLatch(1)
      coordinator.savePolicy(
          coordinator.snapshot().revision,
          MobileAdapterNetworkPolicy.Offline,
          eventBus,
      ) {
        completed.countDown()
      }
      assertTrue(persistence.started.await(5, TimeUnit.SECONDS))

      assertFalse(
          coordinator.applyRuntimeAuthorization(
              displayedRevision,
              networkConsent = true,
              privateLocalDevelopment = true,
              eventBus = eventBus,
          ))
      assertFalse(coordinator.snapshot().networkConsent)
      assertFalse(coordinator.snapshot().privateLocalDevelopment)

      persistence.release.countDown()
      assertTrue(completed.await(5, TimeUnit.SECONDS))
      assertEquals(MobileAdapterNetworkPolicy.Offline, coordinator.snapshot().configuration.networkPolicy)
      assertEquals(refreshes.sorted(), refreshes)
      assertEquals(refreshes.distinct().size, refreshes.size)
    } finally {
      persistence.release.countDown()
      coordinator.close()
      eventBus.close()
    }
  }

  @Test
  fun `save result directly revokes authorization granted on its interim revision`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-coordinator-result-revocation")
    val persistence = BlockingOwnerOnlyWriter()
    val coordinator =
        MobileAdapterConfigurationCoordinator(
            customConfiguration(),
            MobileAdapterConfigurationStore(directory.resolve("adapter.bin"), persistence),
        )
    val eventBus = EventBusImpl()
    try {
      eventBus.register<Controller.CancelMobileAdapterNetworkEvent> {
        throw IllegalStateException("injected cancellation subscriber failure")
      }
      eventBus.register<Controller.RefreshMobileAdapterConfigurationEvent> {
        throw IllegalStateException("injected refresh subscriber failure")
      }

      val completed = CountDownLatch(1)
      coordinator.savePolicy(
          coordinator.snapshot().revision,
          MobileAdapterNetworkPolicy.Offline,
          eventBus,
      ) { completed.countDown() }
      assertTrue(persistence.started.await(5, TimeUnit.SECONDS))

      val interim = coordinator.snapshot()
      assertTrue(interim.configuration.networkPolicy is MobileAdapterNetworkPolicy.CustomServer)
      assertTrue(
          coordinator.applyRuntimeAuthorization(
              interim.revision,
              networkConsent = true,
              privateLocalDevelopment = true,
              eventBus = eventBus,
          ))
      val backend = assertNotNull(coordinator.provider.load().networkBackend)
      val admittedGeneration = backend.generation()

      persistence.release.countDown()
      assertTrue(completed.await(5, TimeUnit.SECONDS))

      val reconciled = coordinator.snapshot()
      assertEquals(MobileAdapterNetworkPolicy.Offline, reconciled.configuration.networkPolicy)
      assertFalse(reconciled.networkConsent)
      assertFalse(reconciled.privateLocalDevelopment)
      assertTrue(admittedGeneration !== backend.generation())
      assertEquals(
          OfferResult.UNAVAILABLE,
          backend.offer(
              admittedGeneration,
              BackendRequest(1, 0x28, byteArrayOf('x'.code.toByte())),
          ),
      )
    } finally {
      persistence.release.countDown()
      coordinator.close()
      eventBus.close()
    }
  }

  @Test
  fun `concurrent runtime transitions publish cancel and refresh in revision order`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-coordinator-transition-order")
    val coordinator =
        MobileAdapterConfigurationCoordinator(
            customConfiguration(),
            MobileAdapterConfigurationStore(directory.resolve("adapter.bin")),
        )
    val eventBus = EventBusImpl()
    val firstCancelEntered = CountDownLatch(1)
    val releaseFirstCancel = CountDownLatch(1)
    try {
      val firstCancelTimedOut = AtomicReference(false)
      val cancelCalls = AtomicInteger()
      val refreshes = Collections.synchronizedList(mutableListOf<Long>())
      eventBus.register<Controller.CancelMobileAdapterNetworkEvent> {
        if (cancelCalls.incrementAndGet() == 1) {
          firstCancelEntered.countDown()
          if (!releaseFirstCancel.await(5, TimeUnit.SECONDS)) {
            firstCancelTimedOut.set(true)
          }
        }
      }
      eventBus.register<Controller.RefreshMobileAdapterConfigurationEvent> {
        refreshes += it.revision
      }

      val firstDone = CountDownLatch(1)
      val firstFailure = AtomicReference<Throwable?>()
      Thread(
              {
                try {
                  assertTrue(
                      coordinator.applyRuntimeAuthorization(
                          coordinator.snapshot().revision,
                          networkConsent = true,
                          privateLocalDevelopment = false,
                          eventBus = eventBus,
                      ))
                } catch (failure: Throwable) {
                  firstFailure.set(failure)
                } finally {
                  firstDone.countDown()
                }
              },
              "mobile-adapter-first-transition-test",
          )
          .apply {
            isDaemon = true
            start()
          }
      assertTrue(firstCancelEntered.await(5, TimeUnit.SECONDS))
      val interimRevision = coordinator.snapshot().revision

      val secondAttempting = CountDownLatch(1)
      val secondDone = CountDownLatch(1)
      val secondFailure = AtomicReference<Throwable?>()
      Thread(
              {
                try {
                  secondAttempting.countDown()
                  assertTrue(
                      coordinator.applyRuntimeAuthorization(
                          interimRevision,
                          networkConsent = false,
                          privateLocalDevelopment = false,
                          eventBus = eventBus,
                      ))
                } catch (failure: Throwable) {
                  secondFailure.set(failure)
                } finally {
                  secondDone.countDown()
                }
              },
              "mobile-adapter-second-transition-test",
          )
          .apply {
            isDaemon = true
            start()
          }
      assertTrue(secondAttempting.await(5, TimeUnit.SECONDS))
      assertFalse(
          secondDone.await(100, TimeUnit.MILLISECONDS),
          "a newer transition must wait until the older transition publishes its refresh",
      )

      releaseFirstCancel.countDown()
      assertTrue(firstDone.await(5, TimeUnit.SECONDS))
      assertTrue(secondDone.await(5, TimeUnit.SECONDS))
      assertFalse(firstCancelTimedOut.get())
      assertNull(firstFailure.get())
      assertNull(secondFailure.get())
      val finalState = coordinator.snapshot()
      assertEquals(listOf(interimRevision, finalState.revision), refreshes)
      assertEquals(2, cancelCalls.get())
      assertFalse(finalState.networkConsent)
      assertFalse(finalState.privateLocalDevelopment)
    } finally {
      releaseFirstCancel.countDown()
      coordinator.close()
      eventBus.close()
    }
  }

  @Test
  fun `reentrant newer transition suppresses the outer stale refresh`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-coordinator-reentrant-order")
    val coordinator =
        MobileAdapterConfigurationCoordinator(
            customConfiguration(),
            MobileAdapterConfigurationStore(directory.resolve("adapter.bin")),
        )
    val eventBus = EventBusImpl()
    try {
      val cancelCalls = AtomicInteger()
      val nestedApplied = AtomicReference(false)
      val refreshes = mutableListOf<Long>()
      eventBus.register<Controller.CancelMobileAdapterNetworkEvent> {
        if (cancelCalls.incrementAndGet() == 1) {
          nestedApplied.set(
              coordinator.applyRuntimeAuthorization(
                  coordinator.snapshot().revision,
                  networkConsent = true,
                  privateLocalDevelopment = true,
                  eventBus = eventBus,
              ))
        }
      }
      eventBus.register<Controller.RefreshMobileAdapterConfigurationEvent> {
        refreshes += it.revision
      }

      assertTrue(
          coordinator.applyRuntimeAuthorization(
              coordinator.snapshot().revision,
              networkConsent = false,
              privateLocalDevelopment = false,
              eventBus = eventBus,
          ))

      val finalState = coordinator.snapshot()
      assertTrue(nestedApplied.get())
      assertTrue(finalState.networkConsent)
      assertTrue(finalState.privateLocalDevelopment)
      assertEquals(2, cancelCalls.get())
      assertEquals(listOf(finalState.revision), refreshes)
    } finally {
      coordinator.close()
      eventBus.close()
    }
  }

  @Test
  fun `stale dialog revision cannot overwrite a completed policy save`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-coordinator-stale-save")
    val persistence = BlockingOwnerOnlyWriter()
    val store = MobileAdapterConfigurationStore(directory.resolve("adapter.bin"), persistence)
    val coordinator = MobileAdapterConfigurationCoordinator(customConfiguration(), store)
    val eventBus = EventBusImpl()
    try {
      val displayedRevision = coordinator.snapshot().revision
      val events = Collections.synchronizedList(mutableListOf<String>())
      eventBus.register<Controller.CancelMobileAdapterNetworkEvent> { events += "cancel" }
      eventBus.register<Controller.RefreshMobileAdapterConfigurationEvent> {
        events += "refresh:${it.revision}"
      }

      val firstCompleted = CountDownLatch(1)
      coordinator.savePolicy(
          displayedRevision,
          MobileAdapterNetworkPolicy.Offline,
          eventBus,
      ) { firstCompleted.countDown() }
      assertTrue(persistence.started.await(5, TimeUnit.SECONDS))
      persistence.release.countDown()
      assertTrue(firstCompleted.await(5, TimeUnit.SECONDS))

      val committed = coordinator.snapshot()
      val eventsBeforeStaleSave = events.toList()
      val staleCompleted = CountDownLatch(1)
      val staleResult = AtomicReference<MobileAdapterConfigurationSaveResult>()
      coordinator.savePolicy(
          displayedRevision,
          customConfiguration().networkPolicy,
          eventBus,
      ) { result ->
        staleResult.set(result)
        staleCompleted.countDown()
      }

      assertTrue(staleCompleted.await(1, TimeUnit.SECONDS))
      assertFalse(staleResult.get().saved)
      assertEquals(
          MobileAdapterConfigurationError.CONFIGURATION_STALE,
          staleResult.get().error,
      )
      assertEquals(1, persistence.writes.get())
      assertEquals(committed, coordinator.snapshot())
      assertEquals(MobileAdapterNetworkPolicy.Offline, store.current().networkPolicy)
      assertEquals(eventsBeforeStaleSave, events)
    } finally {
      persistence.release.countDown()
      coordinator.close()
      eventBus.close()
    }
  }

  @Test
  fun `close interrupts and joins an in-flight policy writer within its bounded deadline`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-coordinator-close")
    val persistence = BlockingOwnerOnlyWriter()
    val coordinator =
        MobileAdapterConfigurationCoordinator(
            customConfiguration(),
            MobileAdapterConfigurationStore(directory.resolve("adapter.bin"), persistence),
        )
    val eventBus = EventBusImpl()
    try {
      val completed = CountDownLatch(1)
      var saved = true
      coordinator.savePolicy(
          coordinator.snapshot().revision,
          MobileAdapterNetworkPolicy.Offline,
          eventBus,
      ) { result ->
        saved = result.saved
        completed.countDown()
      }
      assertTrue(persistence.started.await(5, TimeUnit.SECONDS))

      coordinator.close()

      assertTrue(persistence.interrupted.await(1, TimeUnit.SECONDS))
      assertTrue(completed.await(1, TimeUnit.SECONDS))
      assertFalse(saved)
    } finally {
      persistence.release.countDown()
      coordinator.close()
      eventBus.close()
    }
  }

  @Test
  fun `controller detach immediately before coordinator close is still joined`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-coordinator-detach-close")
    val coordinator =
        MobileAdapterConfigurationCoordinator(
            customConfiguration(),
            MobileAdapterConfigurationStore(directory.resolve("adapter.bin")),
        )
    val backend = assertNotNull(coordinator.provider.load().networkBackend)

    backend.close() // Simulates the controller endpoint lifecycle detaching first.
    coordinator.close()

    assertTrue(backend.isTerminated())
    assertTrue(backend.awaitTermination(0))
  }

  @Test
  fun `prepared backend owner loops admit the exact boundary and reject boundary plus one`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-coordinator-backend-bound")
    val coordinator =
        MobileAdapterConfigurationCoordinator(
            customConfiguration(),
            MobileAdapterConfigurationStore(directory.resolve("adapter.bin")),
        )
    try {
      val prepared =
          List(MobileAdapterConfigurationCoordinator.MAX_TRACKED_NETWORK_BACKENDS) {
            assertNotNull(coordinator.provider.load().networkBackend)
          }
      val failure =
          assertFailsWith<Controller.SerialPeripheralPreparationException> {
            coordinator.provider.load()
          }
      assertEquals(Controller.SerialPeripheralError.ENDPOINT_UNAVAILABLE, failure.error)
      assertEquals(
          MobileAdapterConfigurationCoordinator.MAX_TRACKED_NETWORK_BACKENDS,
          prepared.count { !it.isTerminated() },
      )
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun `subscriber failure cannot split cancellation from revoked endpoint replacement`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-coordinator-subscriber")
    val coordinator =
        MobileAdapterConfigurationCoordinator(
            customConfiguration(),
            MobileAdapterConfigurationStore(directory.resolve("adapter.bin")),
        )
    val eventBus = EventBusImpl()
    try {
      eventBus.register<Controller.CancelMobileAdapterNetworkEvent> {
        throw IllegalStateException("injected presentation subscriber failure")
      }
      eventBus.register<Controller.RefreshMobileAdapterConfigurationEvent> {
        throw IllegalStateException("injected refresh subscriber failure")
      }

      assertTrue(
          coordinator.applyRuntimeAuthorization(
              coordinator.snapshot().revision,
              true,
              true,
              eventBus,
          ))
      val prepared = coordinator.provider.load()
      val backend = assertNotNull(prepared.networkBackend)
      val admittedGeneration = backend.generation()

      assertTrue(
          coordinator.applyRuntimeAuthorization(
              coordinator.snapshot().revision,
              false,
              false,
              eventBus,
          ))
      assertTrue(admittedGeneration !== backend.generation())
      assertEquals(
          OfferResult.UNAVAILABLE,
          backend.offer(admittedGeneration, BackendRequest(1, 0x28, byteArrayOf('x'.code.toByte()))),
      )

      val completed = CountDownLatch(1)
      var saved = false
      coordinator.savePolicy(
          coordinator.snapshot().revision,
          MobileAdapterNetworkPolicy.Offline,
          eventBus,
      ) { result ->
        saved = result.saved
        completed.countDown()
      }
      assertTrue(completed.await(5, TimeUnit.SECONDS))
      assertTrue(saved)
      assertNull(coordinator.provider.load().networkBackend)
    } finally {
      coordinator.close()
      eventBus.close()
    }
  }

  @Test
  fun `configuration writer admits one active and one pending save then rejects boundary plus one`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-coordinator-queue")
    val persistence = BlockingOwnerOnlyWriter()
    val coordinator =
        MobileAdapterConfigurationCoordinator(
            customConfiguration(),
            MobileAdapterConfigurationStore(directory.resolve("adapter.bin"), persistence),
        )
    val eventBus = EventBusImpl()
    try {
      val completed = CountDownLatch(3)
      val results =
          Collections.synchronizedList(mutableListOf<MobileAdapterConfigurationSaveResult>())
      val record = { result: MobileAdapterConfigurationSaveResult ->
        results += result
        completed.countDown()
      }

      coordinator.savePolicy(
          coordinator.snapshot().revision,
          MobileAdapterNetworkPolicy.Offline,
          eventBus,
          record,
      )
      assertTrue(persistence.started.await(5, TimeUnit.SECONDS))
      coordinator.savePolicy(
          coordinator.snapshot().revision,
          customConfiguration().networkPolicy,
          eventBus,
          record,
      )
      coordinator.savePolicy(
          coordinator.snapshot().revision,
          MobileAdapterNetworkPolicy.Offline,
          eventBus,
          record,
      )

      assertTrue(
          results.any { it.error == MobileAdapterConfigurationError.CONFIGURATION_BUSY },
          "the boundary-plus-one edit must fail immediately with a typed busy result",
      )
      persistence.release.countDown()
      assertTrue(completed.await(5, TimeUnit.SECONDS))
      assertEquals(2, results.count { it.saved })
      assertEquals(
          1,
          results.count { it.error == MobileAdapterConfigurationError.CONFIGURATION_BUSY },
      )
    } finally {
      persistence.release.countDown()
      coordinator.close()
      eventBus.close()
    }
  }

  @Test
  fun `close retries termination after an earlier shared deadline expires`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-coordinator-retry")
    val persistence = DeadlineIgnoringOwnerOnlyWriter()
    val coordinator =
        MobileAdapterConfigurationCoordinator(
            customConfiguration(),
            MobileAdapterConfigurationStore(directory.resolve("adapter.bin"), persistence),
        )
    val eventBus = EventBusImpl()
    try {
      val completed = CountDownLatch(1)
      coordinator.savePolicy(
          coordinator.snapshot().revision,
          MobileAdapterNetworkPolicy.Offline,
          eventBus,
      ) { completed.countDown() }
      assertTrue(persistence.started.await(5, TimeUnit.SECONDS))

      val failure = assertFailsWith<IllegalStateException> { coordinator.close() }
      assertTrue(failure.message.orEmpty().contains("shutdown deadline"))
      assertTrue(persistence.interrupts.get() > 0)

      persistence.release.countDown()
      assertTrue(completed.await(5, TimeUnit.SECONDS))
      coordinator.close()
    } finally {
      persistence.release.countDown()
      coordinator.close()
      eventBus.close()
    }
  }

  @Test
  fun `mapping editor parser accepts canonical rows and rejects malformed or excessive input`() {
    assertEquals(
        listOf(
            MobileAdapterPortMapping(MobileAdapterTransport.TCP, 80, 18080),
            MobileAdapterPortMapping(MobileAdapterTransport.UDP, 53, 15353),
        ),
        parseMobileAdapterMappings("TCP 80 18080\nUDP 53 15353"),
    )
    kotlin.test.assertFailsWith<IllegalArgumentException> {
      parseMobileAdapterMappings("TCP 0 80")
    }
    kotlin.test.assertFailsWith<IllegalArgumentException> {
      parseMobileAdapterMappings((1..17).joinToString("\n") { "TCP $it $it" })
    }
    assertEquals(16, parseMobileAdapterMappings((1..16).joinToString("\n") { "TCP $it $it" }).size)
    val totalBoundary =
        (List(15) { " ".repeat(MAX_MOBILE_ADAPTER_MAPPING_LINE_CHARS) } + " ".repeat(49))
            .joinToString("\n")
    assertEquals(MAX_MOBILE_ADAPTER_MAPPING_TEXT_CHARS, totalBoundary.length)
    assertTrue(parseMobileAdapterMappings(totalBoundary).isEmpty())
    assertFailsWith<IllegalArgumentException> {
      parseMobileAdapterMappings(totalBoundary + " ")
    }
    assertTrue(parseMobileAdapterMappings(" ".repeat(MAX_MOBILE_ADAPTER_MAPPING_LINE_CHARS)).isEmpty())
    assertFailsWith<IllegalArgumentException> {
      parseMobileAdapterMappings(" ".repeat(MAX_MOBILE_ADAPTER_MAPPING_LINE_CHARS + 1))
    }
    assertEquals(
        MobileAdapterPortMapping(MobileAdapterTransport.TCP, 65_535, 1),
        parseMobileAdapterMappings("TCP 65535 00001").single(),
    )
    assertFailsWith<IllegalArgumentException> {
      parseMobileAdapterMappings("TCP 65536 1")
    }
    assertFailsWith<IllegalArgumentException> {
      parseMobileAdapterMappings("TCP １２ 80")
    }
  }

  @Test
  fun `configuration document retains its exact boundary and rejects boundary plus one`() {
    val document = PlainDocument()
    document.documentFilter =
        MobileAdapterBoundedDocumentFilter(MAX_MOBILE_ADAPTER_MAPPING_TEXT_CHARS)
    val boundary = "x".repeat(MAX_MOBILE_ADAPTER_MAPPING_TEXT_CHARS)

    document.insertString(0, boundary, null)
    assertEquals(MAX_MOBILE_ADAPTER_MAPPING_TEXT_CHARS, document.length)
    document.insertString(document.length, "y", null)
    assertEquals(boundary, document.getText(0, document.length))
    document.replace(0, document.length, boundary + "z", null)
    assertEquals(boundary, document.getText(0, document.length))
    document.replace(0, document.length, "valid", null)
    assertEquals("valid", document.getText(0, document.length))
  }

  private fun customConfiguration(): MobileAdapterConfiguration =
      MobileAdapterConfiguration(
          0x08,
          ByteArray(MobileAdapterConfiguration.CONFIGURATION_SIZE),
          MobileAdapterNetworkPolicy.CustomServer(
              "service.example",
              "127.0.0.1",
              5353,
              listOf(
                  MobileAdapterPortMapping(MobileAdapterTransport.TCP, 80, 18080),
                  MobileAdapterPortMapping(MobileAdapterTransport.UDP, 53, 15353),
              ),
          ),
      )

  private fun closePrepared(prepared: Controller.MobileAdapterConfiguration) {
    val backend = assertNotNull(prepared.networkBackend)
    backend.close()
    assertTrue(backend.awaitTermination(2_000))
  }

  private class BlockingOwnerOnlyWriter : AtomicFileWriter() {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val interrupted = CountDownLatch(1)
    val writes = AtomicInteger()

    override fun writeOwnerOnly(target: Path, intendedBytes: ByteArray) {
      writes.incrementAndGet()
      started.countDown()
      try {
        if (!release.await(5, TimeUnit.SECONDS)) {
          throw IOException("timed out waiting to release Mobile Adapter policy write")
        }
      } catch (interrupted: InterruptedException) {
        this.interrupted.countDown()
        Thread.currentThread().interrupt()
        throw IOException("Mobile Adapter policy write was interrupted", interrupted)
      }
      AtomicFileWriter.system().writeOwnerOnly(target, intendedBytes)
    }
  }

  private class BlockingThenFailOwnerOnlyWriter : AtomicFileWriter() {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    private val calls = AtomicInteger()

    override fun writeOwnerOnly(target: Path, intendedBytes: ByteArray) {
      if (calls.incrementAndGet() == 1) {
        started.countDown()
        try {
          if (!release.await(5, TimeUnit.SECONDS)) {
            throw IOException("timed out waiting to release first Mobile Adapter policy write")
          }
        } catch (interrupted: InterruptedException) {
          Thread.currentThread().interrupt()
          throw IOException("first Mobile Adapter policy write was interrupted", interrupted)
        }
        AtomicFileWriter.system().writeOwnerOnly(target, intendedBytes)
      } else {
        throw IOException("injected later Mobile Adapter policy write failure")
      }
    }
  }

  private class DeadlineIgnoringOwnerOnlyWriter : AtomicFileWriter() {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val interrupts = AtomicInteger()

    override fun writeOwnerOnly(target: Path, intendedBytes: ByteArray) {
      started.countDown()
      while (true) {
        try {
          release.await()
          break
        } catch (_: InterruptedException) {
          interrupts.incrementAndGet()
        }
      }
      AtomicFileWriter.system().writeOwnerOnly(target, intendedBytes)
    }
  }

  private class FailOwnerOnlyWriter : AtomicFileWriter() {
    override fun writeOwnerOnly(target: Path, intendedBytes: ByteArray) {
      throw IOException("injected Mobile Adapter policy write failure")
    }
  }
}
