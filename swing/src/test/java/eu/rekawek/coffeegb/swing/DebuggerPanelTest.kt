package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugAddressSpace
import eu.rekawek.coffeegb.core.debug.DebugApuState
import eu.rekawek.coffeegb.core.debug.DebugBreakpointList
import eu.rekawek.coffeegb.core.debug.DebugCapabilities
import eu.rekawek.coffeegb.core.debug.DebugCpuState
import eu.rekawek.coffeegb.core.debug.DebugErrorCode
import eu.rekawek.coffeegb.core.debug.DebugExecutionState
import eu.rekawek.coffeegb.core.debug.DebugFeatureState
import eu.rekawek.coffeegb.core.debug.DebugInspectionAnchor
import eu.rekawek.coffeegb.core.debug.DebugInspectionRequest
import eu.rekawek.coffeegb.core.debug.DebugInspectionResult
import eu.rekawek.coffeegb.core.debug.DebugInterruptState
import eu.rekawek.coffeegb.core.debug.DebugMapperState
import eu.rekawek.coffeegb.core.debug.DebugMemoryBlock
import eu.rekawek.coffeegb.core.debug.DebugPpuMode
import eu.rekawek.coffeegb.core.debug.DebugPpuState
import eu.rekawek.coffeegb.core.debug.DebugRegisters
import eu.rekawek.coffeegb.core.debug.DebugResult
import eu.rekawek.coffeegb.core.debug.DebugSnapshot
import eu.rekawek.coffeegb.core.debug.DebugStepKind
import eu.rekawek.coffeegb.core.debug.DebugStepResult
import eu.rekawek.coffeegb.core.debug.DebugTimerState
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryCapabilities
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryConfiguration
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryStatus
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryTruncationReason
import eu.rekawek.coffeegb.core.debug.history.DebugReverseStepResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DebuggerPanelTest {

  @Test
  fun `older refresh completion cannot overwrite a newer pause command snapshot`() {
    val client = RecordingDebuggerClient(1, capabilities())
    val panel = attach(client)
    try {
      assertEquals(1, client.inspections.size)

      onEdt { panel.pauseButton.doClick() }
      assertEquals(1, client.pauses.size)
      assertContains(onEdt { panel.statusLabel.text }, "Pause")

      client.pauses.single().complete(DebugResult.success(snapshot(1, 2, paused = true)))
      flushEdt()
      assertContains(onEdt { panel.snapshotLabel.text }, "snapshot 2")
      assertContains(onEdt { panel.snapshotLabel.text }, "PAUSED")

      val oldRefresh = client.inspections.first()
      oldRefresh.completion.complete(
          DebugResult.success(inspection(oldRefresh.request, snapshot(1, 1, paused = false))))
      flushEdt()

      assertContains(onEdt { panel.snapshotLabel.text }, "snapshot 2")
      assertContains(onEdt { panel.snapshotLabel.text }, "PAUSED")
      assertEquals(2, client.inspections.size)
      assertEquals(
          listOf(DebugInspectionAnchor.PROGRAM_COUNTER, DebugInspectionAnchor.STACK_POINTER),
          client.inspections.last().request.anchoredRequests().map { it.anchor() },
      )
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `completion from a replaced client cannot clear or render over the new session`() {
    val oldClient = RecordingDebuggerClient(4, capabilities())
    val newClient = RecordingDebuggerClient(5, capabilities())
    val panel = attach(oldClient)
    try {
      onEdt { panel.updateClient(5, newClient) }
      assertEquals(1, oldClient.inspections.size)
      assertEquals(1, newClient.inspections.size)

      val oldRefresh = oldClient.inspections.single()
      oldRefresh.completion.complete(
          DebugResult.success(inspection(oldRefresh.request, snapshot(4, 99, paused = false))))
      flushEdt()

      assertContains(onEdt { panel.sessionLabel.text }, "Session 5")
      assertFalse(onEdt { panel.snapshotLabel.text }.contains("snapshot 99"))
      assertFalse(onEdt { panel.refreshButton.isEnabled })

      val newRefresh = newClient.inspections.single()
      newRefresh.completion.complete(
          DebugResult.success(inspection(newRefresh.request, snapshot(5, 1, paused = false))))
      flushEdt()

      assertContains(onEdt { panel.snapshotLabel.text }, "Session 5, snapshot 1")
      assertTrue(onEdt { panel.refreshButton.isEnabled })
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `completion after hide cannot restore released session state`() {
    val client = RecordingDebuggerClient(6, capabilities())
    val panel = attach(client)
    try {
      val pending = client.inspections.single()
      onEdt { panel.setPollingActive(false) }

      completeInspection(pending, snapshot(6, 77, paused = true))
      flushEdt()

      assertContains(onEdt { panel.snapshotLabel.text }, "released")
      assertFalse(onEdt { panel.snapshotLabel.text }.contains("snapshot 77"))
      assertContains(onEdt { panel.registersArea.text }, "released")
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `unexpected failures do not expose exception details in the status view`() {
    val client = RecordingDebuggerClient(7, capabilities())
    val panel = attach(client)
    try {
      client.inspections.single().completion.completeExceptionally(
          IllegalStateException("Sensitive path: /private/roms/game.gb"))
      flushEdt()

      assertContains(onEdt { panel.statusLabel.text }, "unexpected internal error")
      assertFalse(onEdt { panel.statusLabel.text }.contains("/private/roms/game.gb"))
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `command completion gates controls and restores state after success or typed failure`() {
    val client = RecordingDebuggerClient(7, capabilities())
    val panel = attach(client)
    try {
      completeInspection(client.inspections.single(), snapshot(7, 1, paused = false))
      flushEdt()
      assertTrue(onEdt { panel.pauseButton.isEnabled })
      assertFalse(onEdt { panel.runButton.isEnabled })

      onEdt { panel.pauseButton.doClick() }
      assertEquals(1, client.pauses.size)
      assertFalse(onEdt { panel.pauseButton.isEnabled })
      assertFalse(onEdt { panel.runButton.isEnabled })
      assertFalse(onEdt { panel.stepInstructionButton.isEnabled })

      client.pauses.single().complete(DebugResult.success(snapshot(7, 2, paused = true)))
      flushEdt()
      assertContains(onEdt { panel.snapshotLabel.text }, "snapshot 2")
      assertContains(onEdt { panel.snapshotLabel.text }, "PAUSED")
      assertTrue(onEdt { panel.runButton.isEnabled })
      assertFalse(onEdt { panel.pauseButton.isEnabled })
      assertTrue(onEdt { panel.stepInstructionButton.isEnabled })
      assertEquals("Pause completed", onEdt { panel.statusLabel.text })

      onEdt { panel.runButton.doClick() }
      assertEquals(1, client.resumes.size)
      assertFalse(onEdt { panel.runButton.isEnabled })
      client.resumes.single().complete(
          DebugResult.failure(DebugErrorCode.ALREADY_RUNNING, "Injected command failure"))
      flushEdt()

      assertContains(onEdt { panel.statusLabel.text }, "ALREADY_RUNNING")
      assertContains(onEdt { panel.statusLabel.text }, "Injected command failure")
      assertTrue(onEdt { panel.runButton.isEnabled })
      assertFalse(onEdt { panel.pauseButton.isEnabled })
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `inspection planning reserves anchored bytes inside the negotiated aggregate bound`() {
    val client = RecordingDebuggerClient(8, capabilities(maxInspectionBytes = 256))
    val panel = attach(client)
    try {
      completeInspection(client.inspections.single(), snapshot(8, 1, paused = true))
      flushEdt()
      assertEquals(2, client.inspections.size)

      completeInspection(client.inspections.last(), snapshot(8, 2, paused = true))
      flushEdt()
      assertEquals(2, client.inspections.size)

      onEdt {
        panel.memoryRange.text = "\$C000-\$C0FF"
        panel.memoryReadButton.doClick()
      }
      assertEquals(2, client.inspections.size)
      assertContains(onEdt { panel.statusLabel.text }, "237-byte")

      onEdt {
        panel.memoryRange.text = "\$C000-\$C0EC"
        panel.memoryReadButton.doClick()
      }
      assertEquals(3, client.inspections.size)
      val request = client.inspections.last().request
      assertEquals(3, request.blockCount())
      assertEquals(256, request.totalBytes())
      assertEquals(237, request.memoryRequests().single().length())
      assertTrue(request.blockCount() <= client.capabilities.maxInspectionBlocks())
      assertTrue(request.totalBytes() <= client.capabilities.maxInspectionBytes())
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `polling does not submit inspection when coherent inspection is unavailable`() {
    val client = RecordingDebuggerClient(9, capabilities(coherentInspection = false))
    val panel = attach(client)
    try {
      assertTrue(client.inspections.isEmpty())
      assertFalse(onEdt { panel.refreshButton.isEnabled })
      assertContains(onEdt { panel.sessionLabel.text }, "inspection unavailable")
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `unsafe stack pointer is omitted without losing the coherent CPU snapshot`() {
    val client = RecordingDebuggerClient(10, capabilities())
    val panel = attach(client)
    try {
      completeInspection(client.inspections.single(), snapshot(10, 1, paused = true, sp = 0x100))
      flushEdt()

      assertEquals(2, client.inspections.size)
      val request = client.inspections.last().request
      assertEquals(
          listOf(DebugInspectionAnchor.PROGRAM_COUNTER),
          request.anchoredRequests().map { it.anchor() },
      )

      completeInspection(client.inspections.last(), snapshot(10, 2, paused = true, sp = 0x100))
      flushEdt()
      assertContains(onEdt { panel.snapshotLabel.text }, "snapshot 2")
      assertContains(onEdt { panel.stackArea.text }, "unavailable")
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `register boundary race retries through a snapshot-only inspection`() {
    val client = RecordingDebuggerClient(11, capabilities())
    val panel = attach(client)
    try {
      completeInspection(client.inspections.single(), snapshot(11, 1, paused = true))
      flushEdt()

      val staleAnchoredCall = client.inspections.last()
      assertTrue(staleAnchoredCall.request.anchoredRequests().isNotEmpty())
      staleAnchoredCall.completion.complete(
          DebugResult.failure(
              DebugErrorCode.SIDE_EFFECTFUL_ADDRESS,
              "Registers crossed a safe inspection boundary",
          ))
      flushEdt()

      assertEquals(3, client.inspections.size)
      val snapshotOnlyCall = client.inspections.last()
      assertEquals(0, snapshotOnlyCall.request.blockCount())

      completeInspection(
          snapshotOnlyCall,
          snapshot(11, 2, paused = true, pc = 0x7fff, sp = 0x100),
      )
      flushEdt()

      assertEquals(4, client.inspections.size)
      val replanned = client.inspections.last().request
      assertEquals(1, replanned.anchoredRequests().size)
      assertEquals(DebugInspectionAnchor.PROGRAM_COUNTER, replanned.anchoredRequests().single().anchor())
      assertEquals(1, replanned.anchoredRequests().single().length())
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `hiding releases rendered debug state before the retained window can reopen`() {
    val client = RecordingDebuggerClient(12, capabilities())
    val panel = attach(client)
    try {
      completeInspection(client.inspections.single(), snapshot(12, 1, paused = true))
      flushEdt()
      completeInspection(client.inspections.last(), snapshot(12, 2, paused = true))
      flushEdt()
      assertContains(onEdt { panel.snapshotLabel.text }, "snapshot 2")

      onEdt { panel.setPollingActive(false) }

      assertContains(onEdt { panel.snapshotLabel.text }, "released")
      assertContains(onEdt { panel.registersArea.text }, "released")
      assertContains(onEdt { panel.memoryArea.text }, "released")
      assertFalse(onEdt { panel.snapshotLabel.text }.contains("snapshot 2"))
      assertFalse(onEdt { panel.refreshButton.isEnabled })
      assertEquals(0, onEdt { panel.breakpointModel.rowCount })
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `running to paused transition and manual refresh reload reverse metadata`() {
    val client = RecordingDebuggerClient(13, capabilities(history = true))
    val panel = attach(client)
    try {
      assertEquals(1, client.historyRequests.size)
      client.historyRequests.single().complete(DebugResult.success(emptyHistoryStatus()))
      completeInspection(client.inspections.single(), snapshot(13, 1, paused = false))
      flushEdt()

      onEdt { panel.requestRefresh() }
      completeInspection(client.inspections.last(), snapshot(13, 2, paused = true))
      flushEdt()
      assertEquals(2, client.historyRequests.size)

      client.historyRequests.last().complete(DebugResult.success(emptyHistoryStatus()))
      completeInspection(client.inspections.last(), snapshot(13, 3, paused = true))
      flushEdt()
      onEdt { panel.refreshButton.doClick() }
      assertEquals(3, client.historyRequests.size)
      assertEquals(4, client.inspections.size)
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `history toggle keeps authoritative state when configuration fails`() {
    val client = RecordingDebuggerClient(14, capabilities(history = true))
    val panel = attach(client)
    try {
      client.historyRequests.single().complete(DebugResult.success(emptyHistoryStatus()))
      flushEdt()
      assertFalse(onEdt { panel.historyToggle.isSelected })

      onEdt { panel.historyToggle.doClick() }

      assertEquals(1, client.historyConfigurations.size)
      assertTrue(client.historyConfigurations.single().configuration.enabled())
      assertFalse(onEdt { panel.historyToggle.isSelected })

      client.historyConfigurations.single().completion.complete(
          DebugResult.failure(
              DebugErrorCode.UNSUPPORTED_TOPOLOGY,
              "Injected history configuration failure",
          ))
      flushEdt()

      assertFalse(onEdt { panel.historyToggle.isSelected })
      assertContains(onEdt { panel.statusLabel.text }, "UNSUPPORTED_TOPOLOGY")
      assertContains(onEdt { panel.statusLabel.text }, "Injected history configuration failure")
    } finally {
      onEdt(panel::close)
    }
  }

  private fun attach(client: RecordingDebuggerClient): DebuggerPanel =
      onEdt {
        DebuggerPanel(
                clientFactory = { error("DebugPort factory is not used by this test") },
                pollingIntervalMillis = 60_000,
            )
            .also { panel ->
              panel.updateClient(client.generation, client)
              panel.setPollingActive(true)
            }
      }

  private fun completeInspection(call: InspectionCall, snapshot: DebugSnapshot) {
    call.completion.complete(DebugResult.success(inspection(call.request, snapshot)))
  }

  private fun inspection(
      request: DebugInspectionRequest,
      snapshot: DebugSnapshot,
  ): DebugInspectionResult {
    val anchored =
        request.anchoredRequests().map { anchored ->
          val resolved = anchored.resolve(snapshot)
          memoryBlock(resolved.addressSpace(), resolved.address(), resolved.length())
        }
    val memory =
        request.memoryRequests().map { range ->
          memoryBlock(range.addressSpace(), range.address(), range.length())
        }
    return DebugInspectionResult(snapshot, request, anchored, memory)
  }

  private fun memoryBlock(
      addressSpace: DebugAddressSpace,
      address: Int,
      length: Int,
  ): DebugMemoryBlock =
      DebugMemoryBlock(
          addressSpace,
          address,
          ByteArray(length) { index -> if (index == 0) 0 else index.toByte() },
      )

  private fun snapshot(
      generation: Long,
      sequence: Long,
      paused: Boolean,
      pc: Int = 0x100,
      sp: Int = 0xc000,
  ): DebugSnapshot =
      DebugSnapshot(
          generation,
          sequence,
          sequence * 10,
          sequence,
          0,
          paused,
          DebugRegisters(0x12, 0xb0, 0x34, 0x56, 0x78, 0x9a, 0xbc, 0xde, sp, pc),
          DebugInterruptState(true, false, 0x01, 0x01, 0x01),
          DebugTimerState(0x1234, 0x10, 0x20, 0x04, false, 0),
          DebugPpuState(
              true,
              DebugPpuMode.OAM_SEARCH,
              0,
              0,
              0x91,
              0x82,
              0,
              0,
              0,
              0,
              0,
          ),
          DebugApuState(true, 0, false, false, false, false, 0, 0, 0x80),
          DebugMapperState(
              "test",
              -1,
              -1,
              DebugFeatureState.UNKNOWN,
              DebugFeatureState.UNKNOWN,
              DebugFeatureState.UNKNOWN,
          ),
          DebugExecutionState(DebugCpuState.OPCODE_FETCH, 0, -1, 0, false, false, sequence),
      )

  private fun capabilities(
      maxInspectionBytes: Int = 4096,
      coherentInspection: Boolean = true,
      history: Boolean = false,
  ): DebugCapabilities =
      if (coherentInspection) {
        DebugCapabilities(
            true,
            true,
            true,
            false,
            true,
            true,
            true,
            maxInspectionBytes,
            emptySet(),
            0,
            emptySet(),
            0,
            0,
            if (history) {
              DebugHistoryCapabilities(
                  true,
                  true,
                  true,
                  DebugHistoryConfiguration.DEFAULT_MAX_FRAMES,
                  DebugHistoryConfiguration.DEFAULT_MEMORY_BUDGET_BYTES,
              )
            } else {
              DebugHistoryCapabilities.disabled()
            },
        )
      } else {
        DebugCapabilities(false, false, false, false, false, false, false, 0)
      }

  private fun emptyHistoryStatus(): DebugHistoryStatus =
      DebugHistoryStatus(
          DebugHistoryConfiguration.disabled(),
          0,
          0L,
          0L,
          null,
          null,
          null,
          0,
          DebugHistoryTruncationReason.NONE,
      )

  private fun flushEdt() {
    onEdt { Unit }
  }

  private fun <T> onEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    val task = FutureTask(action)
    SwingUtilities.invokeAndWait(task)
    return task.get()
  }

  private data class InspectionCall(
      val request: DebugInspectionRequest,
      val completion: CompletableFuture<DebugResult<DebugInspectionResult>>,
  )

  private data class HistoryConfigurationCall(
      val configuration: DebugHistoryConfiguration,
      val completion: CompletableFuture<DebugResult<DebugHistoryStatus>>,
  )

  private class RecordingDebuggerClient(
      override val generation: Long,
      override val capabilities: DebugCapabilities,
  ) : DebuggerClient {
    val inspections = mutableListOf<InspectionCall>()
    val pauses = mutableListOf<CompletableFuture<DebugResult<DebugSnapshot>>>()
    val resumes = mutableListOf<CompletableFuture<DebugResult<DebugSnapshot>>>()
    val historyRequests = mutableListOf<CompletableFuture<DebugResult<DebugHistoryStatus>>>()
    val historyConfigurations = mutableListOf<HistoryConfigurationCall>()

    override fun inspect(
        request: DebugInspectionRequest
    ): CompletionStage<DebugResult<DebugInspectionResult>> =
        CompletableFuture<DebugResult<DebugInspectionResult>>().also { completion ->
          inspections += InspectionCall(request, completion)
        }

    override fun pause(): CompletionStage<DebugResult<DebugSnapshot>> =
        CompletableFuture<DebugResult<DebugSnapshot>>().also(pauses::add)

    override fun resume(): CompletionStage<DebugResult<DebugSnapshot>> =
        CompletableFuture<DebugResult<DebugSnapshot>>().also(resumes::add)

    override fun step(kind: DebugStepKind): CompletionStage<DebugResult<DebugStepResult>> =
        unsupported()

    override fun stepBackward(
        kind: DebugStepKind
    ): CompletionStage<DebugResult<DebugReverseStepResult>> = unsupported()

    override fun configureHistory(
        configuration: DebugHistoryConfiguration
    ): CompletionStage<DebugResult<DebugHistoryStatus>> =
        CompletableFuture<DebugResult<DebugHistoryStatus>>().also { completion ->
          historyConfigurations += HistoryConfigurationCall(configuration, completion)
        }

    override fun historyStatus(): CompletionStage<DebugResult<DebugHistoryStatus>> =
        CompletableFuture<DebugResult<DebugHistoryStatus>>().also(historyRequests::add)

    override fun listBreakpoints(): CompletionStage<DebugResult<DebugBreakpointList>> =
        CompletableFuture.completedFuture(DebugResult.success(DebugBreakpointList(emptyList())))

    override fun setBreakpoint(
        breakpoint: DebugBreakpoint
    ): CompletionStage<DebugResult<DebugBreakpoint>> = unsupported()

    override fun removeBreakpoint(
        breakpointId: DebugBreakpointId
    ): CompletionStage<DebugResult<Void>> = unsupported()

    private fun <T> unsupported(): CompletionStage<DebugResult<T>> =
        CompletableFuture.completedFuture(
            DebugResult.failure(DebugErrorCode.UNSUPPORTED_TOPOLOGY, "Unsupported in panel test"))
  }
}
