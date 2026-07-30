package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.Controller.SerialPeripheralError
import eu.rekawek.coffeegb.controller.Controller.SerialPeripheralSelection
import eu.rekawek.coffeegb.controller.Controller.SerialPeripheralStatus
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.network.ConnectionController.StartClientEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.StartServerEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.StopServerEvent
import eu.rekawek.coffeegb.core.events.EventBusImpl
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class SerialPeripheralMenuBindingTest {

  @Test
  fun `radio choices enforce one owner and post only typed selection requests`() {
    val eventBus = EventBusImpl()
    try {
      val requested = Collections.synchronizedList(mutableListOf<SerialPeripheralSelection>())
      val realNetworkActions = Collections.synchronizedList(mutableListOf<Any>())
      eventBus.register<Controller.SetSerialPeripheralEvent> { requested += it.selection }
      eventBus.register<StartClientEvent> { realNetworkActions += it }
      eventBus.register<StartServerEvent> { realNetworkActions += it }

      val binding = onEdtResult { SerialPeripheralMenuBinding(eventBus) }
      onEdt {
        binding.items.getValue(SerialPeripheralSelection.MOBILE_ADAPTER_GB).doClick()
        assertEquals(
            listOf(SerialPeripheralSelection.MOBILE_ADAPTER_GB),
            binding.items.filterValues { it.isSelected }.keys.toList(),
        )
        binding.items.getValue(SerialPeripheralSelection.PRINTER).doClick()
        assertEquals(
            listOf(SerialPeripheralSelection.PRINTER),
            binding.items.filterValues { it.isSelected }.keys.toList(),
        )
      }

      assertEquals(
          listOf(
              SerialPeripheralSelection.MOBILE_ADAPTER_GB,
              SerialPeripheralSelection.PRINTER,
          ),
          requested,
      )
      assertTrue(realNetworkActions.isEmpty(), "offline selection must not post network actions")
    } finally {
      eventBus.close()
    }
  }

  @Test
  fun `controller status is copied immutably and rendered on EDT`() {
    val eventBus = EventBusImpl()
    try {
      val callbacksOnEdt = AtomicBoolean(true)
      val rendered = Collections.synchronizedList(mutableListOf<SerialPeripheralUiSnapshot>())
      val attached = CountDownLatch(1)
      val binding =
          onEdtResult {
            SerialPeripheralMenuBinding(
                eventBus,
                onRendered = { snapshot ->
                  callbacksOnEdt.compareAndSet(true, SwingUtilities.isEventDispatchThread())
                  rendered += snapshot
                  if (snapshot.status == SerialPeripheralStatus.ATTACHED) attached.countDown()
                },
            )
          }

      val publisher =
          thread(name = "serial-peripheral-ui-test") {
            eventBus.post(
                Controller.SerialPeripheralSelectionChangedEvent(
                    SerialPeripheralSelection.MOBILE_ADAPTER_GB))
            eventBus.post(
                Controller.SerialPeripheralStatusEvent(
                    SerialPeripheralSelection.MOBILE_ADAPTER_GB,
                    SerialPeripheralStatus.ATTACHED,
                ))
          }
      publisher.join()
      assertTrue(attached.await(3, TimeUnit.SECONDS))
      flushEdt()

      assertTrue(callbacksOnEdt.get())
      assertEquals(SerialPeripheralSelection.MOBILE_ADAPTER_GB, binding.snapshot().selection)
      assertEquals(SerialPeripheralStatus.ATTACHED, binding.snapshot().status)
      assertTrue(binding.statusItem.text.contains("network offline"))
      assertTrue(rendered.size >= 3)
      assertNotSame(rendered[rendered.lastIndex - 1], rendered.last())

      eventBus.post(
          Controller.SerialPeripheralStatusEvent(
              SerialPeripheralSelection.MOBILE_ADAPTER_GB,
              SerialPeripheralStatus.DETACHED,
          ))
      flushEdt()
      assertEquals(SerialPeripheralStatus.DETACHED, binding.snapshot().status)
      assertTrue(binding.statusItem.text.contains("detached"))
    } finally {
      eventBus.close()
    }
  }

  @Test
  fun `mobile network status rejects stale attachments and renders bounded connection count`() {
    val eventBus = EventBusImpl()
    try {
      val binding = onEdtResult { SerialPeripheralMenuBinding(eventBus) }
      eventBus.post(
          Controller.SerialPeripheralSelectionChangedEvent(
              SerialPeripheralSelection.MOBILE_ADAPTER_GB))
      eventBus.post(
          Controller.SerialPeripheralStatusEvent(
              SerialPeripheralSelection.MOBILE_ADAPTER_GB,
              SerialPeripheralStatus.ATTACHED,
          ))
      eventBus.post(
          Controller.MobileAdapterNetworkStatusEvent(
              attachmentId = 20,
              policyRevision = 4,
              phase = Controller.MobileAdapterNetworkPhase.CONNECTED,
              activeConnections = 2,
          ))
      eventBus.post(
          Controller.MobileAdapterNetworkStatusEvent(
              attachmentId = 19,
              policyRevision = 3,
              phase = Controller.MobileAdapterNetworkPhase.FAILED,
              error = Controller.MobileAdapterNetworkError.IO_FAILED,
          ))
      flushEdt()

      assertTrue(binding.statusItem.text.contains("connected (2/2)"))
      assertFalse(binding.statusItem.text.contains("IO_FAILED"))
      assertFalse(binding.statusItem.toolTipText.contains("host"))
    } finally {
      eventBus.close()
    }
  }

  @Test
  fun `remote close identifies one slot without hiding the surviving connection`() {
    val eventBus = EventBusImpl()
    try {
      val binding = onEdtResult { SerialPeripheralMenuBinding(eventBus) }
      eventBus.post(
          Controller.SerialPeripheralSelectionChangedEvent(
              SerialPeripheralSelection.MOBILE_ADAPTER_GB))
      eventBus.post(
          Controller.SerialPeripheralStatusEvent(
              SerialPeripheralSelection.MOBILE_ADAPTER_GB,
              SerialPeripheralStatus.ATTACHED,
          ))
      eventBus.post(
          Controller.MobileAdapterNetworkStatusEvent(
              attachmentId = 21,
              policyRevision = 5,
              phase = Controller.MobileAdapterNetworkPhase.FAILED,
              slot = 0,
              activeConnections = 1,
              error = Controller.MobileAdapterNetworkError.REMOTE_CLOSED,
          ))
      flushEdt()

      assertTrue(binding.statusItem.text.contains("slot 0 closed"))
      assertTrue(binding.statusItem.text.contains("1/2 connections remain"))
      assertTrue(binding.statusItem.text.contains("REMOTE_CLOSED"))
      assertFalse(binding.statusItem.text.contains("host"))
    } finally {
      eventBus.close()
    }
  }

  @Test
  fun `typed unavailable status has bounded presentation-safe text`() {
    val eventBus = EventBusImpl()
    try {
      val binding = onEdtResult { SerialPeripheralMenuBinding(eventBus) }
      eventBus.post(
          Controller.SerialPeripheralStatusEvent(
              SerialPeripheralSelection.MOBILE_ADAPTER_GB,
              SerialPeripheralStatus.UNAVAILABLE,
              SerialPeripheralError.STORAGE_FAILED,
          ))
      flushEdt()

      val snapshot = binding.snapshot()
      val text = binding.statusItem.text
      assertEquals(SerialPeripheralError.STORAGE_FAILED, snapshot.error)
      assertTrue(text.contains("STORAGE_FAILED"))
      assertTrue(text.contains(SerialPeripheralError.STORAGE_FAILED.userMessage))
      assertFalse(text.contains('/'))
      assertFalse(text.contains('\\'))
      assertTrue(text.length < 256)
    } finally {
      eventBus.close()
    }
  }

  @Test
  fun `unavailable handoff restores the radio item for the committed owner`() {
    val eventBus = EventBusImpl()
    try {
      val binding = onEdtResult { SerialPeripheralMenuBinding(eventBus) }
      onEdt {
        binding.items.getValue(SerialPeripheralSelection.MOBILE_ADAPTER_GB).doClick()
        assertTrue(binding.isSelected(SerialPeripheralSelection.MOBILE_ADAPTER_GB))
      }

      eventBus.post(
          Controller.SerialPeripheralStatusEvent(
              SerialPeripheralSelection.MOBILE_ADAPTER_GB,
              SerialPeripheralStatus.UNAVAILABLE,
              SerialPeripheralError.CONFIGURATION_INVALID,
          ))
      flushEdt()

      assertEquals(SerialPeripheralSelection.PEER_TO_PEER, binding.snapshot().selection)
      assertEquals(
          SerialPeripheralSelection.MOBILE_ADAPTER_GB,
          binding.snapshot().statusSelection,
      )
      assertTrue(binding.isSelected(SerialPeripheralSelection.PEER_TO_PEER))
      assertFalse(binding.isSelected(SerialPeripheralSelection.MOBILE_ADAPTER_GB))
    } finally {
      eventBus.close()
    }
  }

  @Test(timeout = 10_000)
  fun `blocking network prerequisite preserves ordering without blocking EDT`() {
    val eventBus = EventBusImpl()
    try {
      val stopEntered = CountDownLatch(1)
      val releaseStop = CountDownLatch(1)
      val selectionPosted = CountDownLatch(1)
      val stopRanOnEdt = AtomicBoolean(true)
      eventBus.register<StopServerEvent> {
        stopRanOnEdt.set(SwingUtilities.isEventDispatchThread())
        stopEntered.countDown()
        releaseStop.await(5, TimeUnit.SECONDS)
      }
      eventBus.register<Controller.SetSerialPeripheralEvent> { selectionPosted.countDown() }
      val binding =
          onEdtResult {
            SerialPeripheralMenuBinding(
                eventBus,
                transitionPrerequisites = {
                  listOf(SerialPeripheralTransitionPrerequisite.STOP_SERVER)
                },
            )
          }

      // doClick returns while the prerequisite subscriber is still blocked on another thread.
      onEdt { binding.items.getValue(SerialPeripheralSelection.MOBILE_ADAPTER_GB).doClick() }
      assertTrue(stopEntered.await(5, TimeUnit.SECONDS))
      assertFalse(stopRanOnEdt.get())
      assertEquals(1L, selectionPosted.count)
      assertFalse(onEdtResult { binding.menu.isEnabled })

      releaseStop.countDown()
      assertTrue(selectionPosted.await(5, TimeUnit.SECONDS))
      awaitCondition { onEdtResult { binding.menu.isEnabled } }
    } finally {
      eventBus.close()
    }
  }

  @Test(timeout = 10_000)
  fun `failed network stop can retry retained linked ownership and then select`() {
    val eventBus = EventBusImpl()
    try {
      val selectionPosts = Collections.synchronizedList(mutableListOf<SerialPeripheralSelection>())
      val unavailable = CountDownLatch(1)
      val selected = CountDownLatch(1)
      val serverSelected = AtomicBoolean(true)
      val linkedControllerActive = AtomicBoolean(true)
      eventBus.register<StopServerEvent> {
        serverSelected.set(false)
        throw IllegalStateException("private failure detail")
      }
      eventBus.register<EnsureStandaloneControllerEvent> {
        linkedControllerActive.set(false)
      }
      eventBus.register<Controller.SetSerialPeripheralEvent> {
        selectionPosts += it.selection
        selected.countDown()
      }
      val binding =
          onEdtResult {
            SerialPeripheralMenuBinding(
                eventBus,
                transitionPrerequisites = { selection ->
                  serialPeripheralTransitionPrerequisites(
                      selection = selection,
                      serverSelected = serverSelected.get(),
                      clientSelected = false,
                      linkedControllerActive = linkedControllerActive.get(),
                  )
                },
                onRendered = {
                  if (it.status == SerialPeripheralStatus.UNAVAILABLE) unavailable.countDown()
                },
            )
          }

      onEdt { binding.items.getValue(SerialPeripheralSelection.MOBILE_ADAPTER_GB).doClick() }
      assertTrue(unavailable.await(5, TimeUnit.SECONDS))
      flushEdt()

      assertTrue(selectionPosts.isEmpty())
      assertTrue(binding.isSelected(SerialPeripheralSelection.PEER_TO_PEER))
      assertEquals(SerialPeripheralStatus.UNAVAILABLE, binding.snapshot().status)
      assertEquals(SerialPeripheralError.PORT_OWNED_BY_LINK, binding.snapshot().error)
      assertFalse(binding.statusItem.text.contains("private failure detail"))
      assertTrue(onEdtResult { binding.menu.isEnabled })

      // Although the network toggle was cleared, the retained linked controller contributes an
      // explicit ENSURE prerequisite, so a second click retries ownership transfer.
      onEdt { binding.items.getValue(SerialPeripheralSelection.MOBILE_ADAPTER_GB).doClick() }
      assertTrue(selected.await(5, TimeUnit.SECONDS))
      flushEdt()
      assertEquals(listOf(SerialPeripheralSelection.MOBILE_ADAPTER_GB), selectionPosts)
      assertFalse(linkedControllerActive.get())
    } finally {
      eventBus.close()
    }
  }

  @Test(timeout = 10_000)
  fun `post commit subscriber failure does not synthesize ownership rollback`() {
    val eventBus = EventBusImpl()
    try {
      val attached = CountDownLatch(1)
      val unavailable = AtomicBoolean()
      val binding =
          onEdtResult {
            SerialPeripheralMenuBinding(
                eventBus,
                transitionPrerequisites = {
                  listOf(SerialPeripheralTransitionPrerequisite.STOP_SERVER)
                },
                onRendered = {
                  if (it.status == SerialPeripheralStatus.ATTACHED) attached.countDown()
                  if (it.status == SerialPeripheralStatus.UNAVAILABLE) unavailable.set(true)
                },
            )
          }
      eventBus.register<Controller.SetSerialPeripheralEvent> { event ->
        eventBus.post(Controller.SerialPeripheralSelectionChangedEvent(event.selection))
        eventBus.post(
            Controller.SerialPeripheralStatusEvent(
                event.selection,
                SerialPeripheralStatus.ATTACHED,
            ))
      }
      eventBus.register<Controller.SetSerialPeripheralEvent> {
        throw IllegalStateException("private later subscriber detail")
      }

      onEdt { binding.items.getValue(SerialPeripheralSelection.MOBILE_ADAPTER_GB).doClick() }
      assertTrue(attached.await(5, TimeUnit.SECONDS))
      awaitCondition { onEdtResult { binding.menu.isEnabled } }

      assertEquals(SerialPeripheralSelection.MOBILE_ADAPTER_GB, binding.snapshot().selection)
      assertEquals(SerialPeripheralStatus.ATTACHED, binding.snapshot().status)
      assertNull(binding.snapshot().error)
      assertTrue(binding.isSelected(SerialPeripheralSelection.MOBILE_ADAPTER_GB))
      assertFalse(unavailable.get())
    } finally {
      eventBus.close()
    }
  }

  @Test
  fun `pre controller subscriber failure restores the authoritative radio selection`() {
    val eventBus = EventBusImpl()
    try {
      eventBus.register<Controller.SetSerialPeripheralEvent> {
        throw IllegalStateException("private early subscriber detail")
      }
      val binding = onEdtResult { SerialPeripheralMenuBinding(eventBus) }

      onEdt { binding.items.getValue(SerialPeripheralSelection.MOBILE_ADAPTER_GB).doClick() }
      flushEdt()

      assertEquals(SerialPeripheralSelection.PEER_TO_PEER, binding.snapshot().selection)
      assertTrue(onEdtResult { binding.isSelected(SerialPeripheralSelection.PEER_TO_PEER) })
      assertFalse(onEdtResult { binding.isSelected(SerialPeripheralSelection.MOBILE_ADAPTER_GB) })
      assertEquals(SerialPeripheralStatus.DETACHED, binding.snapshot().status)
    } finally {
      eventBus.close()
    }
  }

  @Test
  fun `committed controller ownership replacement resets a no session local selection`() {
    val eventBus = EventBusImpl()
    try {
      val binding = onEdtResult { SerialPeripheralMenuBinding(eventBus) }
      eventBus.post(
          Controller.SerialPeripheralSelectionChangedEvent(
              SerialPeripheralSelection.MOBILE_ADAPTER_GB))
      eventBus.post(
          Controller.SerialPeripheralStatusEvent(
              SerialPeripheralSelection.MOBILE_ADAPTER_GB,
              SerialPeripheralStatus.DETACHED,
          ))
      flushEdt()
      assertTrue(onEdtResult { binding.isSelected(SerialPeripheralSelection.MOBILE_ADAPTER_GB) })

      eventBus.post(ControllerOwnershipCommittedEvent())
      flushEdt()

      assertEquals(SerialPeripheralSelection.PEER_TO_PEER, binding.snapshot().selection)
      assertEquals(SerialPeripheralStatus.DETACHED, binding.snapshot().status)
      assertTrue(onEdtResult { binding.isSelected(SerialPeripheralSelection.PEER_TO_PEER) })
      assertFalse(onEdtResult { binding.isSelected(SerialPeripheralSelection.MOBILE_ADAPTER_GB) })
    } finally {
      eventBus.close()
    }
  }

  @Test
  fun `failed controller close retains the attached serial owner until replacement commits`() {
    val eventBus = EventBusImpl()
    try {
      val binding = onEdtResult { SerialPeripheralMenuBinding(eventBus) }
      eventBus.post(
          Controller.SerialPeripheralSelectionChangedEvent(
              SerialPeripheralSelection.MOBILE_ADAPTER_GB))
      eventBus.post(
          Controller.SerialPeripheralStatusEvent(
              SerialPeripheralSelection.MOBILE_ADAPTER_GB,
              SerialPeripheralStatus.ATTACHED,
          ))
      flushEdt()

      assertFailsWith<IOException> {
        eventBus.post(ControllerOwnershipChangingEvent())
        throw IOException("simulated persistence barrier")
      }
      flushEdt()

      assertEquals(SerialPeripheralSelection.MOBILE_ADAPTER_GB, binding.snapshot().selection)
      assertEquals(SerialPeripheralStatus.ATTACHED, binding.snapshot().status)
      assertTrue(onEdtResult { binding.isSelected(SerialPeripheralSelection.MOBILE_ADAPTER_GB) })

      eventBus.post(ControllerOwnershipCommittedEvent())
      flushEdt()
      assertEquals(SerialPeripheralSelection.PEER_TO_PEER, binding.snapshot().selection)
      assertEquals(SerialPeripheralStatus.DETACHED, binding.snapshot().status)
      assertTrue(onEdtResult { binding.isSelected(SerialPeripheralSelection.PEER_TO_PEER) })
    } finally {
      eventBus.close()
    }
  }

  @Test
  fun `production prerequisite policy is stop only and preserves linked retry`() {
    assertEquals(
        listOf(
            SerialPeripheralTransitionPrerequisite.STOP_SERVER,
            SerialPeripheralTransitionPrerequisite.STOP_CLIENT,
            SerialPeripheralTransitionPrerequisite.ENSURE_STANDALONE_CONTROLLER,
        ),
        serialPeripheralTransitionPrerequisites(
            SerialPeripheralSelection.MOBILE_ADAPTER_GB,
            serverSelected = true,
            clientSelected = true,
            linkedControllerActive = true,
        ),
    )
    assertEquals(
        listOf(SerialPeripheralTransitionPrerequisite.ENSURE_STANDALONE_CONTROLLER),
        serialPeripheralTransitionPrerequisites(
            SerialPeripheralSelection.MOBILE_ADAPTER_GB,
            serverSelected = false,
            clientSelected = false,
            linkedControllerActive = true,
        ),
    )
    assertTrue(
        serialPeripheralTransitionPrerequisites(
                SerialPeripheralSelection.PEER_TO_PEER,
                serverSelected = true,
                clientSelected = true,
                linkedControllerActive = true,
            )
            .isEmpty())
  }

  private fun flushEdt() {
    if (SwingUtilities.isEventDispatchThread()) return
    SwingUtilities.invokeAndWait {}
  }

  private fun awaitCondition(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition() && System.nanoTime() < deadline) {
      Thread.sleep(10)
    }
    assertTrue(condition())
  }

  private fun onEdt(action: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
  }

  private fun <T> onEdtResult(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(action) }
    return checkNotNull(result).getOrThrow()
  }
}
