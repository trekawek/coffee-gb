package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugAddressSpace
import eu.rekawek.coffeegb.core.debug.DebugAudioChannelInspection
import eu.rekawek.coffeegb.core.debug.DebugAudioInspection
import eu.rekawek.coffeegb.core.debug.DebugApuState
import eu.rekawek.coffeegb.core.debug.DebugBreakpointList
import eu.rekawek.coffeegb.core.debug.DebugBreakpointHit
import eu.rekawek.coffeegb.core.debug.DebugByteData
import eu.rekawek.coffeegb.core.debug.DebugCapabilities
import eu.rekawek.coffeegb.core.debug.DebugCpuState
import eu.rekawek.coffeegb.core.debug.DebugErrorCode
import eu.rekawek.coffeegb.core.debug.DebugExecutionState
import eu.rekawek.coffeegb.core.debug.DebugFeatureState
import eu.rekawek.coffeegb.core.debug.DebugGraphicsHardwareMode
import eu.rekawek.coffeegb.core.debug.DebugGraphicsInspection
import eu.rekawek.coffeegb.core.debug.DebugInspectionAnchor
import eu.rekawek.coffeegb.core.debug.DebugInspectionRequest
import eu.rekawek.coffeegb.core.debug.DebugInspectionResult
import eu.rekawek.coffeegb.core.debug.DebugInspectionSection
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
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPcCondition
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryCapabilities
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryConfiguration
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryStatus
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryTruncationReason
import eu.rekawek.coffeegb.core.debug.history.DebugReverseStepResult
import eu.rekawek.coffeegb.core.debug.trace.InterruptTrace
import eu.rekawek.coffeegb.core.debug.trace.PpuTrace
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration
import eu.rekawek.coffeegb.core.debug.trace.TraceEntry
import eu.rekawek.coffeegb.core.debug.trace.TraceReadResult
import eu.rekawek.coffeegb.core.debug.trace.TraceSource
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyEvent
import java.util.EnumSet
import java.util.Optional
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RunnableFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import javax.swing.JLabel
import javax.swing.KeyStroke
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
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
  fun `paused unsafe pc and stack produce one replan without an immediate refresh loop`() {
    val client = RecordingDebuggerClient(81, capabilities())
    val panel = attach(client)
    try {
      completeInspection(
          client.inspections.single(),
          snapshot(81, 1, paused = true, pc = 0x8000, sp = 0x8000),
      )
      flushEdt()

      assertEquals(2, client.inspections.size)
      val replan = client.inspections.last()
      assertEquals(0, replan.request.blockCount())
      completeInspection(
          replan,
          snapshot(81, 2, paused = true, pc = 0x8000, sp = 0x8000),
      )
      flushEdt()

      assertEquals(2, client.inspections.size)
      assertContains(onEdt { panel.snapshotLabel.text }, "snapshot 2")
      assertContains(onEdt { panel.disassemblyArea.text }, "outside")
      assertContains(onEdt { panel.stackArea.text }, "unavailable")
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
      assertEquals(0, onEdt { panel.breakpointPane.tableModel.rowCount })
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
  fun `breakpoint metadata identifies the current stop and becomes historical after movement`() {
    val client =
        RecordingDebuggerClient(
            131,
            capabilities(breakpoints = true),
            deferLastBreakpointHit = true,
        )
    val breakpoint =
        DebugBreakpoint(DebugBreakpointId(7), true, DebugPcCondition.at(0x0150))
    client.breakpointDefinitions = listOf(breakpoint)
    val panel = attach(client)
    try {
      val stoppingSnapshot = snapshot(131, 1, paused = true, pc = 0x0150)
      val recapturedSnapshot =
          snapshot(
              131,
              2,
              paused = true,
              pc = 0x0150,
              masterTick = stoppingSnapshot.masterTick(),
              frame = stoppingSnapshot.frame(),
              retiredInstructions = stoppingSnapshot.execution().retiredInstructions(),
          )
      completeInspection(client.inspections.single(), recapturedSnapshot)
      flushEdt()

      client.lastBreakpointHitRequests.single().complete(
          DebugResult.success(DebugBreakpointHit(breakpoint, 9, stoppingSnapshot, true))
      )
      flushEdt()

      assertContains(onEdt { panel.stopReasonLabel.text }, "Stopped by breakpoint #7")
      assertContains(onEdt { panel.stopReasonLabel.text }, "${'$'}0150")
      assertContains(onEdt { panel.stopReasonLabel.text }, "matched tick 9")

      val followUp = client.inspections.last()
      completeInspection(followUp, snapshot(131, 3, paused = true, pc = 0x0151))
      flushEdt()

      assertContains(onEdt { panel.stopReasonLabel.text }, "Last stop: breakpoint #7")
      assertFalse(onEdt { panel.stopReasonLabel.text }.startsWith("Stopped by"))
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `foreign generation breakpoint metadata is rejected by every breakpoint surface`() {
    val client =
        RecordingDebuggerClient(
            132,
            capabilities(breakpoints = true),
            deferLastBreakpointHit = true,
        )
    val breakpoint =
        DebugBreakpoint(DebugBreakpointId(8), true, DebugPcCondition.at(0x0200))
    client.breakpointDefinitions = listOf(breakpoint)
    val panel = attach(client)
    try {
      completeInspection(
          client.inspections.single(),
          snapshot(132, 1, paused = true, pc = 0x0200),
      )
      flushEdt()

      client.lastBreakpointHitRequests.single().complete(
          DebugResult.success(
              DebugBreakpointHit(
                  breakpoint.id(),
                  9,
                  snapshot(999, 1, paused = true, pc = 0x0200),
              ))
      )
      flushEdt()

      assertEquals("No breakpoint stop in this session", onEdt { panel.stopReasonLabel.text })
      assertContains(onEdt { panel.breakpointPane.hitLabel.text }, "No breakpoint hit")
      assertEquals("", onEdt { panel.breakpointPane.tableModel.getValueAt(0, 0) })
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `editing a hit id keeps the captured condition and clears the row marker`() {
    val original = DebugBreakpoint(DebugBreakpointId(7), true, DebugPcCondition.at(0x0150))
    val client =
        RecordingDebuggerClient(
            137,
            capabilities(breakpoints = true),
            deferLastBreakpointHit = true,
        ).apply { breakpointDefinitions = listOf(original) }
    val panel = attach(client)
    try {
      val stoppingSnapshot = snapshot(137, 1, paused = true, pc = 0x0150)
      completeInspection(client.inspections.single(), stoppingSnapshot)
      flushEdt()
      val hit = DebugBreakpointHit(original, 9, stoppingSnapshot, true)
      client.lastBreakpointHitRequests.single().complete(DebugResult.success(hit))
      flushEdt()
      assertEquals("Last hit", onEdt { panel.breakpointPane.tableModel.getValueAt(0, 0) })

      onEdt {
        panel.breakpointPane.table.setRowSelectionInterval(0, 0)
        panel.breakpointPane.editButton.doClick()
        panel.breakpointPane.editor.addressField.text = "\$0160"
        panel.breakpointPane.saveButton.doClick()
      }
      val edit = client.breakpointSets.single()
      assertEquals(original.id(), edit.breakpoint.id())
      assertEquals(DebugPcCondition.at(0x0160), edit.breakpoint.condition())
      client.breakpointDefinitions = listOf(edit.breakpoint)
      edit.completion.complete(DebugResult.success(edit.breakpoint))
      flushEdt()

      assertEquals(2, client.lastBreakpointHitRequests.size)
      client.lastBreakpointHitRequests.last().complete(DebugResult.success(hit))
      flushEdt()

      assertContains(onEdt { panel.stopReasonLabel.text }, "\$0150")
      assertFalse(onEdt { panel.stopReasonLabel.text }.contains("\$0160"))
      assertEquals("", onEdt { panel.breakpointPane.tableModel.getValueAt(0, 0) })
      assertEquals("\$0160", onEdt { panel.breakpointPane.tableModel.getValueAt(0, 4) })
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `breakpoint pane waits for a matching inspection before calling a hit current`() {
    val breakpoint =
        DebugBreakpoint(DebugBreakpointId(10), true, DebugPcCondition.at(0x0500))
    val client =
        RecordingDebuggerClient(
            138,
            capabilities(breakpoints = true),
            deferLastBreakpointHit = true,
        ).apply { breakpointDefinitions = listOf(breakpoint) }
    val panel = attach(client)
    try {
      val stoppingSnapshot = snapshot(138, 1, paused = true, pc = 0x0500)
      client.lastBreakpointHitRequests.single().complete(
          DebugResult.success(DebugBreakpointHit(breakpoint, 9, stoppingSnapshot, true))
      )
      flushEdt()

      assertContains(onEdt { panel.stopReasonLabel.text }, "Last stop:")
      assertContains(onEdt { panel.breakpointPane.hitLabel.text }, "Last hit:")
      assertFalse(onEdt { panel.breakpointPane.hitLabel.text }.contains("Current stop"))

      completeInspection(
          client.inspections.single(),
          snapshot(
              138,
              2,
              paused = true,
              pc = 0x0500,
              masterTick = stoppingSnapshot.masterTick(),
              frame = stoppingSnapshot.frame(),
              retiredInstructions = stoppingSnapshot.execution().retiredInstructions(),
          ),
      )
      flushEdt()

      assertContains(onEdt { panel.stopReasonLabel.text }, "Stopped by breakpoint #10")
      assertContains(onEdt { panel.breakpointPane.hitLabel.text }, "Current stop:")
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `metadata started before a resume cannot restore the old stop reason`() {
    val client =
        RecordingDebuggerClient(
            133,
            capabilities(breakpoints = true),
            deferLastBreakpointHit = true,
        )
    val breakpoint =
        DebugBreakpoint(DebugBreakpointId(9), true, DebugPcCondition.at(0x0300))
    client.breakpointDefinitions = listOf(breakpoint)
    val panel = attach(client)
    try {
      val stoppingSnapshot = snapshot(133, 1, paused = true, pc = 0x0300)
      completeInspection(client.inspections.single(), stoppingSnapshot)
      flushEdt()

      onEdt { panel.runButton.doClick() }
      client.resumes.single().complete(
          DebugResult.success(snapshot(133, 2, paused = false, pc = 0x0300))
      )
      flushEdt()

      client.lastBreakpointHitRequests.single().complete(
          DebugResult.success(DebugBreakpointHit(breakpoint.id(), 9, stoppingSnapshot))
      )
      flushEdt()

      assertEquals(2, client.lastBreakpointHitRequests.size)
      assertEquals("No breakpoint stop in this session", onEdt { panel.stopReasonLabel.text })
      assertContains(onEdt { panel.breakpointPane.hitLabel.text }, "No breakpoint hit")

      client.lastBreakpointHitRequests.last().complete(
          DebugResult.failure(
              DebugErrorCode.NO_BREAKPOINT_HIT,
              "No breakpoint has stopped this session",
          )
      )
      flushEdt()
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `breakpoint CRUD waits for authoritative state and never reuses allocated ids`() {
    val initial = DebugBreakpoint(DebugBreakpointId(5), true, DebugPcCondition.at(0x0150))
    val client = RecordingDebuggerClient(132, capabilities(breakpoints = true))
    client.breakpointDefinitions = listOf(initial)
    val panel = attach(client)
    try {
      completeInspection(
          client.inspections.single(),
          snapshot(132, 1, paused = true, pc = 0x0150),
      )
      flushEdt()
      assertEquals(1, onEdt { panel.breakpointPane.tableModel.rowCount })

      onEdt {
        panel.breakpointPane.editor.addressField.text = "\$0160"
        panel.breakpointPane.saveButton.doClick()
      }

      val firstAdd = client.breakpointSets.single()
      assertEquals(6, firstAdd.breakpoint.id().value())
      assertEquals(DebugPcCondition.at(0x0160), firstAdd.breakpoint.condition())
      assertEquals(1, onEdt { panel.breakpointPane.tableModel.rowCount })

      client.breakpointDefinitions = listOf(initial, firstAdd.breakpoint)
      firstAdd.completion.complete(DebugResult.success(firstAdd.breakpoint))
      flushEdt()
      assertEquals(2, onEdt { panel.breakpointPane.tableModel.rowCount })

      onEdt {
        val modelRow = panel.breakpointPane.tableModel.indexOf(firstAdd.breakpoint.id())
        val viewRow = panel.breakpointPane.table.convertRowIndexToView(modelRow)
        panel.breakpointPane.table.setRowSelectionInterval(viewRow, viewRow)
        panel.breakpointPane.removeButton.doClick()
      }

      val removal = client.breakpointRemovals.single()
      assertEquals(firstAdd.breakpoint.id(), removal.breakpointId)
      assertEquals(2, onEdt { panel.breakpointPane.tableModel.rowCount })

      client.breakpointDefinitions = listOf(initial)
      removal.completion.complete(DebugResult.success())
      flushEdt()
      assertEquals(1, onEdt { panel.breakpointPane.tableModel.rowCount })

      onEdt {
        panel.breakpointPane.editor.addressField.text = "\$0170"
        panel.breakpointPane.saveButton.doClick()
      }

      val secondAdd = client.breakpointSets.last()
      assertEquals(2, client.breakpointSets.size)
      assertEquals(7, secondAdd.breakpoint.id().value())
      assertEquals(DebugPcCondition.at(0x0170), secondAdd.breakpoint.condition())
      assertEquals(1, onEdt { panel.breakpointPane.tableModel.rowCount })
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `session replacement clears breakpoint drafts filters and edit identity`() {
    val oldBreakpoint =
        DebugBreakpoint(DebugBreakpointId(5), true, DebugPcCondition.at(0x0150))
    val oldClient = RecordingDebuggerClient(134, capabilities(breakpoints = true)).apply {
      breakpointDefinitions = listOf(oldBreakpoint)
    }
    val newClient = RecordingDebuggerClient(135, capabilities(breakpoints = true))
    val panel = attach(oldClient)
    try {
      completeInspection(
          oldClient.inspections.single(),
          snapshot(134, 1, paused = true, pc = 0x0150),
      )
      flushEdt()
      onEdt {
        panel.breakpointPane.table.setRowSelectionInterval(0, 0)
        panel.breakpointPane.editButton.doClick()
        panel.breakpointPane.editor.addressField.text = "\$2222"
        panel.breakpointPane.filterField.text = "private old-session draft"

        panel.updateClient(135, newClient)
      }
      flushEdt()

      assertEquals("", onEdt { panel.breakpointPane.filterField.text })
      assertEquals("\$0100", onEdt { panel.breakpointPane.editor.addressField.text })
      assertEquals(0, onEdt { panel.breakpointPane.tableModel.rowCount })

      onEdt {
        panel.breakpointPane.editor.addressField.text = "\$0200"
        panel.breakpointPane.saveButton.doClick()
      }
      assertEquals(1, newClient.breakpointSets.size)
      assertEquals(0, newClient.breakpointSets.single().breakpoint.id().value())
      assertEquals(DebugPcCondition.at(0x0200), newClient.breakpointSets.single().breakpoint.condition())
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `global F9 removes every duplicate breakpoint at the paused PC`() {
    val first = DebugBreakpoint(DebugBreakpointId(9), true, DebugPcCondition.at(0x0400))
    val second = DebugBreakpoint(DebugBreakpointId(2), true, DebugPcCondition.at(0x0400))
    val client = RecordingDebuggerClient(136, capabilities(breakpoints = true)).apply {
      breakpointDefinitions = listOf(first, second)
    }
    val panel = attach(client)
    try {
      completeInspection(
          client.inspections.single(),
          snapshot(136, 1, paused = true, pc = 0x0400),
      )
      flushEdt()

      onEdt {
        val input = panel.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        val actionName = input.get(KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0))
        panel.actionMap.get(actionName).actionPerformed(null)
      }

      assertEquals(listOf(DebugBreakpointId(2)), client.breakpointRemovals.map { it.breakpointId })
      assertEquals(2, onEdt { panel.breakpointPane.tableModel.rowCount })
      client.breakpointRemovals.single().completion.complete(DebugResult.success())
      flushEdt()

      assertEquals(
          listOf(DebugBreakpointId(2), DebugBreakpointId(9)),
          client.breakpointRemovals.map { it.breakpointId },
      )
      client.breakpointDefinitions = emptyList()
      client.breakpointRemovals.last().completion.complete(DebugResult.success())
      flushEdt()

      assertEquals(0, onEdt { panel.breakpointPane.tableModel.rowCount })
      assertContains(onEdt { panel.breakpointPane.editorStatusLabel.text }, "Removed 2")
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

  @Test
  fun `timeline is explicit opt in and consumes bounded coherent cursor pages`() {
    val client = RecordingDebuggerClient(15, capabilities(trace = true))
    val panel = attach(client)
    try {
      completeInspection(client.inspections.single(), snapshot(15, 1, paused = false))
      flushEdt()
      assertTrue(client.traceConfigurations.isEmpty())
      assertFalse(onEdt { panel.timelineToggle.isSelected })

      onEdt { panel.timelineToggle.doClick() }
      assertEquals(1, client.traceConfigurations.size)
      val enable = client.traceConfigurations.single()
      assertTrue(enable.configuration.isEnabled())
      assertTrue(enable.configuration.capacity() <= 2_000)
      assertFalse(TraceCategory.CPU in enable.configuration.categories())
      assertFalse(TraceCategory.MEMORY in enable.configuration.categories())
      enable.completion.complete(DebugResult.success(enable.configuration))
      flushEdt()

      assertEquals(2, client.inspections.size)
      val firstPage = client.inspections.last()
      assertTrue(firstPage.request.traceRequest().isPresent)
      assertEquals(-1L, firstPage.request.traceRequest().get().afterSequence())
      assertTrue(firstPage.request.traceRequest().get().maxEntries() <= 256)
      val tracePage =
          TraceReadResult(
              listOf(
                  TraceEntry(
                      5,
                      100,
                      TraceSource.PPU,
                      PpuTrace(PpuTrace.Kind.SCANLINE_STARTED, 0, 0, 0, DebugPpuMode.OAM_SEARCH),
                  ),
                  TraceEntry(
                      6,
                      101,
                      TraceSource.INTERRUPT_CONTROLLER,
                      InterruptTrace(
                          InterruptTrace.Kind.REQUESTED,
                          eu.rekawek.coffeegb.core.debug.DebugInterruptType.VBLANK,
                      ),
                  ),
              ),
              6,
              2,
              3,
              5,
              7,
          )
      completeInspection(firstPage, snapshot(15, 2, paused = false), tracePage)
      flushEdt()

      assertEquals(2, onEdt { panel.timelineModel.rowCount })
      assertEquals("S15/#2", onEdt { panel.timelineModel.rowAt(0).identity.label })
      assertContains(onEdt { panel.timelineWarning.text }, "missed 2")
      assertContains(onEdt { panel.timelineWarning.text }, "dropped 3")

      onEdt { panel.requestRefresh() }
      val nextPage = client.inspections.last().request.traceRequest().orElseThrow()
      assertEquals(6L, nextPage.afterSequence())
      assertTrue(nextPage.maxEntries() <= client.capabilities.maxInspectionTraceEntries())
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `hide disables only timeline tracing that this window enabled`() {
    val untouchedClient = RecordingDebuggerClient(16, capabilities(trace = true))
    val untouchedPanel = attach(untouchedClient)
    completeInspection(untouchedClient.inspections.single(), snapshot(16, 1, paused = false))
    flushEdt()
    onEdt { untouchedPanel.setPollingActive(false) }
    assertTrue(untouchedClient.traceConfigurations.isEmpty())
    onEdt(untouchedPanel::close)

    val ownedClient = RecordingDebuggerClient(17, capabilities(trace = true))
    val ownedPanel = attach(ownedClient)
    try {
      completeInspection(ownedClient.inspections.single(), snapshot(17, 1, paused = false))
      flushEdt()
      onEdt { ownedPanel.timelineToggle.doClick() }
      val enable = ownedClient.traceConfigurations.single()

      // Hiding while enable is queued must still enqueue a matching disable after it succeeds.
      onEdt { ownedPanel.setPollingActive(false) }
      assertEquals(0, onEdt { ownedPanel.timelineModel.rowCount })
      assertFalse(onEdt { ownedPanel.timelineToggle.isSelected })
      enable.completion.complete(DebugResult.success(enable.configuration))
      flushEdt()

      assertEquals(2, ownedClient.traceConfigurations.size)
      val disable = ownedClient.traceConfigurations.last()
      assertFalse(disable.configuration.isEnabled())
      disable.completion.complete(DebugResult.success(disable.configuration))
      flushEdt()
      assertContains(onEdt { ownedPanel.timelineWarning.text }, "off")
    } finally {
      onEdt(ownedPanel::close)
    }
  }

  @Test
  fun `replacement abandons a terminal old timeline owner after bounded disable retries`() {
    val oldClient =
        RecordingDebuggerClient(
            22,
            capabilities(trace = true),
            failTraceDisable = true,
        )
    val newClient = RecordingDebuggerClient(23, capabilities(trace = true))
    val panel = attach(oldClient)
    try {
      completeInspection(oldClient.inspections.single(), snapshot(22, 1, paused = false))
      flushEdt()
      onEdt { panel.timelineToggle.doClick() }
      val enable = oldClient.traceConfigurations.single()
      enable.completion.complete(DebugResult.success(enable.configuration))
      flushEdt()
      assertTrue(onEdt { panel.timelineToggle.isSelected })

      onEdt { panel.updateClient(23, newClient) }
      assertTrue(oldClient.traceConfigurations.drop(1).all { !it.configuration.isEnabled() })
      completeInspection(newClient.inspections.single(), snapshot(23, 1, paused = false))
      flushEdt()

      assertFalse(onEdt { panel.timelineToggle.isSelected })
      assertTrue(onEdt { panel.timelineToggle.isEnabled })
      onEdt { panel.timelineToggle.doClick() }
      assertEquals(1, newClient.traceConfigurations.size)
      assertTrue(newClient.traceConfigurations.single().configuration.isEnabled())
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `peripheral inspection requests only the selected supported pane`() {
    val client =
        RecordingDebuggerClient(
            18,
            capabilities(
                inspectionSections = EnumSet.allOf(DebugInspectionSection::class.java),
            ),
        )
    val panel = attach(client)
    try {
      val cpuCall = client.inspections.single()
      assertTrue(cpuCall.request.sections().isEmpty())
      completeInspection(cpuCall, snapshot(18, 1, paused = false))
      flushEdt()

      onEdt { panel.tabs.selectedComponent = panel.graphicsPane }
      val graphicsCall = client.inspections.last()
      assertEquals(setOf(DebugInspectionSection.GRAPHICS), graphicsCall.request.sections())
      graphicsCall.completion.complete(
          DebugResult.failure(DebugErrorCode.UNSUPPORTED_TOPOLOGY, "Stop graphics test request"))
      flushEdt()

      onEdt { panel.tabs.selectedComponent = panel.audioPane }
      val audioCall = client.inspections.last()
      assertEquals(setOf(DebugInspectionSection.AUDIO), audioCall.request.sections())
      audioCall.completion.complete(
          DebugResult.failure(DebugErrorCode.UNSUPPORTED_TOPOLOGY, "Stop audio test request"))
      flushEdt()

      onEdt { panel.tabs.selectedComponent = panel.timelinePane }
      assertTrue(client.inspections.last().request.sections().isEmpty())
    } finally {
      onEdt(panel::close)
    }
  }

  @Test
  fun `stale peripheral preparation is rejected and hide releases rendered payload views`() {
    val executor = ManualExecutorService()
    val client =
        RecordingDebuggerClient(
            19,
            capabilities(inspectionSections = setOf(DebugInspectionSection.GRAPHICS)),
        )
    val panel = attach(client, executor)
    try {
      completeInspection(client.inspections.single(), snapshot(19, 1, paused = false))
      flushEdt()
      onEdt { panel.tabs.selectedComponent = panel.graphicsPane }

      val staleCall = client.inspections.last()
      completeInspection(
          staleCall,
          snapshot(19, 2, paused = false),
          graphics = graphicsInspection(),
      )
      assertEquals(1, executor.queuedTaskCount)

      onEdt { panel.setPollingActive(false) }
      assertEquals(true, executor.lastCancellationMayInterrupt)
      executor.runNext()
      flushEdt()
      assertContains(onEdt { panel.graphicsPane.overviewArea.text }, "No graphics")
      assertEquals(0, onEdt { panel.graphicsPane.tileTable.rowCount })

      onEdt { panel.setPollingActive(true) }
      val currentCall = client.inspections.last()
      assertEquals(setOf(DebugInspectionSection.GRAPHICS), currentCall.request.sections())
      completeInspection(
          currentCall,
          snapshot(19, 3, paused = false),
          graphics = graphicsInspection(),
      )
      executor.runNext()
      flushEdt()
      assertTrue(onEdt { panel.graphicsPane.tileTable.rowCount } > 0)

      onEdt { panel.setPollingActive(false) }
      assertContains(onEdt { panel.graphicsPane.overviewArea.text }, "No graphics")
      assertEquals(0, onEdt { panel.graphicsPane.tileTable.rowCount })
    } finally {
      onEdt(panel::close)
      executor.shutdownNow()
    }
  }

  @Test
  fun `peripheral panes never retain rows under a newer snapshot identity`() {
    val executor = ManualExecutorService()
    val client =
        RecordingDebuggerClient(
            20,
            capabilities(
                inspectionSections = EnumSet.allOf(DebugInspectionSection::class.java),
            ),
        )
    val panel = attach(client, executor)
    try {
      completeInspection(client.inspections.single(), snapshot(20, 1, paused = false))
      flushEdt()

      onEdt { panel.tabs.selectedComponent = panel.graphicsPane }
      val graphicsCall = client.inspections.last()
      completeInspection(
          graphicsCall,
          snapshot(20, 2, paused = false),
          graphics = graphicsInspection(),
      )
      executor.runNext()
      flushEdt()
      assertTrue(onEdt { panel.graphicsPane.tileTable.rowCount } > 0)
      assertContains(onEdt { panel.graphicsPane.overviewArea.text }, "snapshot 2")
      assertContains(onEdt { panel.snapshotLabel.text }, "snapshot 2")

      onEdt { panel.tabs.selectedIndex = 0 }
      val cpuCall = client.inspections.last()
      assertTrue(cpuCall.request.sections().isEmpty())
      completeInspection(cpuCall, snapshot(20, 3, paused = false))
      flushEdt()
      assertContains(onEdt { panel.snapshotLabel.text }, "snapshot 3")
      assertContains(onEdt { panel.graphicsPane.overviewArea.text }, "snapshot 3")
      assertContains(onEdt { panel.graphicsPane.overviewArea.text }, "not captured")
      assertEquals(0, onEdt { panel.graphicsPane.tileTable.rowCount })

      onEdt { panel.tabs.selectedComponent = panel.audioPane }
      val audioCall = client.inspections.last()
      completeInspection(
          audioCall,
          snapshot(20, 4, paused = false),
          audio = audioInspection(),
      )
      executor.runNext()
      flushEdt()
      assertEquals(4, onEdt { panel.audioPane.channelTable.rowCount })
      assertContains(onEdt { panel.audioPane.overviewArea.text }, "snapshot 4")

      onEdt { panel.pauseButton.doClick() }
      client.pauses.single().complete(DebugResult.success(snapshot(20, 5, paused = true)))
      flushEdt()
      assertContains(onEdt { panel.snapshotLabel.text }, "snapshot 5")
      assertContains(onEdt { panel.audioPane.overviewArea.text }, "snapshot 5")
      assertContains(onEdt { panel.audioPane.overviewArea.text }, "not captured")
      assertEquals(0, onEdt { panel.audioPane.channelTable.rowCount })
    } finally {
      onEdt(panel::close)
      executor.shutdownNow()
    }
  }

  @Test
  fun `post submit epoch race interrupts stale peripheral preparation`() {
    val executor = BlockingSubmitExecutorService()
    val client =
        RecordingDebuggerClient(
            21,
            capabilities(inspectionSections = setOf(DebugInspectionSection.GRAPHICS)),
        )
    val panel = attach(client, executor)
    try {
      completeInspection(client.inspections.single(), snapshot(21, 1, paused = false))
      flushEdt()
      onEdt { panel.tabs.selectedComponent = panel.graphicsPane }
      val graphicsCall = client.inspections.last()
      val completionThread =
          Thread(
              {
                completeInspection(
                    graphicsCall,
                    snapshot(21, 2, paused = false),
                    graphics = graphicsInspection(),
                )
              },
              "debugger-blocked-submit-test",
          )
      completionThread.start()
      assertTrue(executor.awaitSubmission(5, TimeUnit.SECONDS))

      onEdt { panel.setPollingActive(false) }
      executor.releaseSubmission()
      completionThread.join(5_000)
      assertFalse(completionThread.isAlive)
      assertEquals(true, executor.lastCancellationMayInterrupt)
      flushEdt()
      assertContains(onEdt { panel.graphicsPane.overviewArea.text }, "No graphics")
    } finally {
      executor.releaseSubmission()
      onEdt(panel::close)
      executor.shutdownNow()
    }
  }

  @Test
  fun `keyboard accessibility font scaling copy and preferences stay presentation only`() {
    var copied = ""
    val initial =
        DebuggerUiPreferences(
            selectedPane = 0,
            fontScalePercent = 120,
            timelineCategories = setOf(TraceCategory.CPU, TraceCategory.TIMER),
            timelineCapacity = 512,
        )
    val panel =
        onEdt {
          DebuggerPanel(
              clientFactory = { error("not used") },
              pollingIntervalMillis = 60_000,
              initialPreferences = initial,
              copyText = { copied = it },
          )
        }
    try {
      val labels = onEdt { descendants(panel).filterIsInstance<JLabel>() }
      assertSame(panel.memorySpace, labels.single { it.text == "Space:" }.labelFor)
      assertSame(panel.memoryRange, labels.single { it.text == "Range:" }.labelFor)
      assertSame(panel.timelineCapacity, labels.single { it.text == "Capacity:" }.labelFor)
      assertTrue(onEdt { panel.refreshButton.mnemonic != 0 })
      assertNotNull(
          onEdt {
            panel
                .getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0))
          })
      assertNotNull(
          onEdt {
            panel
                .getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0))
          })
      assertNotNull(
          onEdt {
            panel
                .getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0))
          })
      assertNotNull(
          onEdt {
            panel
                .getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0))
          })
      assertNotNull(
          onEdt {
            panel
                .getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0))
          })

      val rowHeight = onEdt { panel.timelineTable.rowHeight }
      onEdt {
        val input = panel.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        val zoomKey = input.get(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, debuggerMenuShortcutMask()))
        panel.actionMap.get(zoomKey).actionPerformed(null)
      }
      assertEquals(130, onEdt { panel.preferences().fontScalePercent })
      assertTrue(onEdt { panel.timelineTable.rowHeight } > rowHeight)

      onEdt {
        panel.registersArea.text = "copyable registers"
        val input = panel.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        val copyKey = input.get(KeyStroke.getKeyStroke(KeyEvent.VK_C, debuggerMenuShortcutMask()))
        panel.actionMap.get(copyKey).actionPerformed(null)
      }
      assertContains(copied, "copyable registers")
      assertEquals(
          onEdt { panel.statusLabel.text },
          onEdt { panel.statusLabel.accessibleContext.accessibleDescription },
      )

      val saved = onEdt { panel.preferences() }
      assertEquals(512, saved.timelineCapacity)
      assertEquals(setOf(TraceCategory.CPU, TraceCategory.TIMER), saved.timelineCategories)
    } finally {
      onEdt(panel::close)
    }
  }

  private fun attach(
      client: RecordingDebuggerClient,
      peripheralExecutor: AbstractExecutorService? = null,
  ): DebuggerPanel =
      onEdt {
        val panel =
            if (peripheralExecutor == null) {
              DebuggerPanel(
                  clientFactory = { error("DebugPort factory is not used by this test") },
                  pollingIntervalMillis = 60_000,
              )
            } else {
              DebuggerPanel(
                  clientFactory = { error("DebugPort factory is not used by this test") },
                  pollingIntervalMillis = 60_000,
                  peripheralExecutor = peripheralExecutor,
                  ownsPeripheralExecutor = false,
              )
            }
        panel
            .also { panel ->
              panel.updateClient(client.generation, client)
              panel.setPollingActive(true)
            }
      }

  private fun completeInspection(
      call: InspectionCall,
      snapshot: DebugSnapshot,
      trace: TraceReadResult? = null,
      graphics: DebugGraphicsInspection? = null,
      audio: DebugAudioInspection? = null,
  ) {
    call.completion.complete(
        DebugResult.success(inspection(call.request, snapshot, trace, graphics, audio)))
  }

  private fun inspection(
      request: DebugInspectionRequest,
      snapshot: DebugSnapshot,
      trace: TraceReadResult? = null,
      graphics: DebugGraphicsInspection? = null,
      audio: DebugAudioInspection? = null,
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
    val traceResult =
        if (request.traceRequest().isPresent) {
          trace
              ?: request.traceRequest().get().let { traceRequest ->
                TraceReadResult(
                    emptyList(),
                    traceRequest.afterSequence(),
                    0,
                    0,
                    0,
                    (traceRequest.afterSequence() + 1).coerceAtLeast(0),
                )
              }
        } else {
          null
        }
    return DebugInspectionResult(
        snapshot,
        request,
        anchored,
        memory,
        Optional.ofNullable(graphics),
        Optional.ofNullable(audio),
        Optional.ofNullable(traceResult),
    )
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
      masterTick: Long = sequence * 10,
      frame: Long = sequence,
      framePosition: Int = 0,
      retiredInstructions: Long = sequence,
  ): DebugSnapshot =
      DebugSnapshot(
          generation,
          sequence,
          masterTick,
          frame,
          framePosition,
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
          DebugExecutionState(
              DebugCpuState.OPCODE_FETCH,
              0,
              -1,
              0,
              false,
              false,
              retiredInstructions,
          ),
      )

  private fun capabilities(
      maxInspectionBytes: Int = 4096,
      coherentInspection: Boolean = true,
      history: Boolean = false,
      trace: Boolean = false,
      breakpoints: Boolean = false,
      inspectionSections: Set<DebugInspectionSection> = emptySet(),
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
            if (breakpoints) EnumSet.allOf(DebugBreakpointKind::class.java) else emptySet(),
            if (breakpoints) 128 else 0,
            if (trace) EnumSet.allOf(TraceCategory::class.java) else emptySet(),
            if (trace) 2_000 else 0,
            if (trace) 256 else 0,
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
            inspectionSections,
            if (trace) 256 else 0,
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

  private fun descendants(root: Component): List<Component> =
      buildList {
        fun visit(component: Component) {
          add(component)
          (component as? Container)?.components?.forEach(::visit)
        }
        visit(root)
      }

  private fun graphicsInspection(): DebugGraphicsInspection =
      DebugGraphicsInspection(
          DebugGraphicsHardwareMode.DMG,
          0,
          0x10,
          0xe4,
          0xd2,
          0x1b,
          -1,
          -1,
          DebugByteData(ByteArray(DebugGraphicsInspection.VRAM_BANK_LENGTH)),
          DebugByteData(ByteArray(0)),
          DebugByteData(ByteArray(DebugGraphicsInspection.OAM_LENGTH)),
          DebugByteData(ByteArray(0)),
          DebugByteData(ByteArray(0)),
      )

  private fun audioInspection(): DebugAudioInspection =
      DebugAudioInspection(
          true,
          5,
          0xfa,
          0x35,
          0xf1,
          (1..4).map { channel ->
            DebugAudioChannelInspection(
                channel,
                channel == 1,
                true,
                channel,
                channel,
                false,
                if (channel == 2 || channel == 4) 0 else 0x80,
                0,
                0xf0,
                0,
                0,
            )
          },
          DebugByteData(ByteArray(DebugAudioInspection.WAVE_RAM_LENGTH)),
      )

  private class ManualExecutorService : AbstractExecutorService() {
    private val tasks = ArrayDeque<Runnable>()
    private var stopped = false
    private var lastTask: RecordingFutureTask<*>? = null

    val queuedTaskCount: Int
      get() = tasks.size

    val lastCancellationMayInterrupt: Boolean?
      get() = lastTask?.cancellationMayInterrupt

    override fun <T> newTaskFor(callable: Callable<T>): RunnableFuture<T> =
        RecordingFutureTask(callable).also { lastTask = it }

    override fun <T> newTaskFor(runnable: Runnable, value: T): RunnableFuture<T> =
        RecordingFutureTask(runnable, value).also { lastTask = it }

    override fun execute(command: Runnable) {
      if (stopped) throw RejectedExecutionException("Manual executor is shut down")
      tasks.addLast(command)
    }

    fun runNext() {
      tasks.removeFirst().run()
    }

    override fun shutdown() {
      stopped = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
      stopped = true
      val pending = tasks.toMutableList()
      tasks.clear()
      return pending
    }

    override fun isShutdown(): Boolean = stopped

    override fun isTerminated(): Boolean = stopped && tasks.isEmpty()

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
  }

  private class BlockingSubmitExecutorService : AbstractExecutorService() {
    private val submitted = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private var stopped = false
    private var lastTask: RecordingFutureTask<*>? = null

    val lastCancellationMayInterrupt: Boolean?
      get() = lastTask?.cancellationMayInterrupt

    override fun <T> newTaskFor(callable: Callable<T>): RunnableFuture<T> =
        RecordingFutureTask(callable).also { lastTask = it }

    override fun <T> newTaskFor(runnable: Runnable, value: T): RunnableFuture<T> =
        RecordingFutureTask(runnable, value).also { lastTask = it }

    override fun execute(command: Runnable) {
      if (stopped) throw RejectedExecutionException("Blocking executor is shut down")
      submitted.countDown()
      check(release.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release submit" }
    }

    fun awaitSubmission(timeout: Long, unit: TimeUnit): Boolean = submitted.await(timeout, unit)

    fun releaseSubmission() = release.countDown()

    override fun shutdown() {
      stopped = true
      release.countDown()
    }

    override fun shutdownNow(): MutableList<Runnable> {
      shutdown()
      return mutableListOf()
    }

    override fun isShutdown(): Boolean = stopped

    override fun isTerminated(): Boolean = stopped

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = stopped
  }

  private class RecordingFutureTask<T> : FutureTask<T> {
    @Volatile var cancellationMayInterrupt: Boolean? = null
      private set

    constructor(callable: Callable<T>) : super(callable)

    constructor(runnable: Runnable, value: T) : super(runnable, value)

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
      cancellationMayInterrupt = mayInterruptIfRunning
      return super.cancel(mayInterruptIfRunning)
    }
  }

  private data class InspectionCall(
      val request: DebugInspectionRequest,
      val completion: CompletableFuture<DebugResult<DebugInspectionResult>>,
  )

  private data class HistoryConfigurationCall(
      val configuration: DebugHistoryConfiguration,
      val completion: CompletableFuture<DebugResult<DebugHistoryStatus>>,
  )

  private data class TraceConfigurationCall(
      val configuration: TraceConfiguration,
      val completion: CompletableFuture<DebugResult<TraceConfiguration>>,
  )

  private data class BreakpointSetCall(
      val breakpoint: DebugBreakpoint,
      val completion: CompletableFuture<DebugResult<DebugBreakpoint>>,
  )

  private data class BreakpointRemovalCall(
      val breakpointId: DebugBreakpointId,
      val completion: CompletableFuture<DebugResult<Void>>,
  )

  private class RecordingDebuggerClient(
      override val generation: Long,
      override val capabilities: DebugCapabilities,
      private val failTraceDisable: Boolean = false,
      private val deferLastBreakpointHit: Boolean = false,
  ) : DebuggerClient {
    val inspections = mutableListOf<InspectionCall>()
    val pauses = mutableListOf<CompletableFuture<DebugResult<DebugSnapshot>>>()
    val resumes = mutableListOf<CompletableFuture<DebugResult<DebugSnapshot>>>()
    val historyRequests = mutableListOf<CompletableFuture<DebugResult<DebugHistoryStatus>>>()
    val historyConfigurations = mutableListOf<HistoryConfigurationCall>()
    val traceConfigurations = mutableListOf<TraceConfigurationCall>()
    val lastBreakpointHitRequests =
        mutableListOf<CompletableFuture<DebugResult<DebugBreakpointHit>>>()
    val breakpointSets = mutableListOf<BreakpointSetCall>()
    val breakpointRemovals = mutableListOf<BreakpointRemovalCall>()
    var breakpointDefinitions: List<DebugBreakpoint> = emptyList()

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

    override fun configureTrace(
        configuration: TraceConfiguration
    ): CompletionStage<DebugResult<TraceConfiguration>> =
        CompletableFuture<DebugResult<TraceConfiguration>>().also { completion ->
          traceConfigurations += TraceConfigurationCall(configuration, completion)
          if (failTraceDisable && !configuration.isEnabled()) {
            completion.complete(
                DebugResult.failure(DebugErrorCode.PORT_CLOSED, "The old debug port is closed"))
          }
        }

    override fun listBreakpoints(): CompletionStage<DebugResult<DebugBreakpointList>> =
        CompletableFuture.completedFuture(
            DebugResult.success(DebugBreakpointList(breakpointDefinitions))
        )

    override fun lastBreakpointHit(): CompletionStage<DebugResult<DebugBreakpointHit>> =
        CompletableFuture<DebugResult<DebugBreakpointHit>>().also { completion ->
          lastBreakpointHitRequests += completion
          if (!deferLastBreakpointHit) {
            completion.complete(
                DebugResult.failure(
                    DebugErrorCode.NO_BREAKPOINT_HIT,
                    "No breakpoint has stopped this session",
                )
            )
          }
        }

    override fun setBreakpoint(
        breakpoint: DebugBreakpoint
    ): CompletionStage<DebugResult<DebugBreakpoint>> =
        CompletableFuture<DebugResult<DebugBreakpoint>>().also { completion ->
          breakpointSets += BreakpointSetCall(breakpoint, completion)
        }

    override fun removeBreakpoint(
        breakpointId: DebugBreakpointId
    ): CompletionStage<DebugResult<Void>> =
        CompletableFuture<DebugResult<Void>>().also { completion ->
          breakpointRemovals += BreakpointRemovalCall(breakpointId, completion)
        }

    private fun <T> unsupported(): CompletionStage<DebugResult<T>> =
        CompletableFuture.completedFuture(
            DebugResult.failure(DebugErrorCode.UNSUPPORTED_TOPOLOGY, "Unsupported in panel test"))
  }
}
