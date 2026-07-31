package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.swing.io.AudioDeviceSnapshot
import eu.rekawek.coffeegb.swing.io.GamepadCatalog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class DevicePreferencesEditorsTest {

  @Test
  fun `camera choices map human labels to bounded indexes and restore defaults`() =
      onEdt {
        val editor =
            PeripheralsPreferencesEditor(
                ApplicationSettings.Peripherals(cameraDeviceIndex = 7),
                ApplicationSettings.Peripherals(cameraDeviceIndex = 2),
            )

        assertEquals(16, editor.cameraDevice.itemCount)
        assertEquals(
            "Camera 1 (Coffee GB default)",
            (editor.cameraDevice.getItemAt(0) as PeripheralsPreferencesEditor.CameraOption).label,
        )
        assertEquals(
            "Camera 16",
            (editor.cameraDevice.getItemAt(15) as PeripheralsPreferencesEditor.CameraOption).label,
        )
        assertEquals(7, editor.validatedPeripherals().cameraDeviceIndex)
        assertEquals(
            "Game Boy Camera device",
            editor.cameraDevice.accessibleContext.accessibleName,
        )

        editor.cameraDevice.selectedIndex = 15
        assertEquals(15, editor.validatedPeripherals().cameraDeviceIndex)
        editor.restoreDefaults()
        assertEquals(2, editor.validatedPeripherals().cameraDeviceIndex)
      }

  @Test
  fun `peripherals page only exposes supported host peripherals`() =
      onEdt {
        val editor =
            PeripheralsPreferencesEditor(
                ApplicationSettings.Peripherals(),
            )

        assertFalse(
            editor.components.filterIsInstance<javax.swing.JLabel>().any { label ->
              label.text.contains("Mobile Adapter")
            },
        )
      }

  @Test
  fun `gamepad snapshot choices retain unavailable assignments and persist per-device tuning`() =
      onEdt {
        val unavailableId = gamepadId('a')
        val availableId = gamepadId('b')
        val snapshots =
            AtomicReference(
                gamepadSnapshot(
                    GamepadCatalog.Status.AVAILABLE,
                    emptyList(),
                ))
        val initial =
            ApplicationSettings.Input.defaults().copy(
                gamepads =
                    mapOf(
                        0 to ApplicationSettings.GamepadSelection.Device(unavailableId)),
                gamepadTunings =
                    mapOf(
                        unavailableId to
                            ApplicationSettings.GamepadTuning(
                                movementDeadZone = 2_048)),
            )
        val editor =
            GamepadPreferencesEditor(
                initial,
                snapshots = GamepadSnapshotProvider(snapshots::get),
            )

        assertEquals(
            ApplicationSettings.GamepadSelection.Device(unavailableId),
            selectedGamepad(editor, 0),
        )
        assertTrue(editor.playerSelector.selectedItem.toString().contains("Unavailable"))

        snapshots.set(
            gamepadSnapshot(
                GamepadCatalog.Status.AVAILABLE,
                listOf(GamepadCatalog.Device(availableId, "Arcade Pad", null)),
            ))
        editor.refreshCatalog()
        selectGamepad(
            editor,
            1,
            ApplicationSettings.GamepadSelection.Device(availableId),
        )
        selectTuningDevice(editor, availableId)
        editor.movementDeadZone.value = 12_345
        editor.tiltDeadZone.value = 2_345
        editor.invertMovementX.doClick()
        editor.invertTiltY.doClick()

        val draft = editor.validatedDraft()

        assertEquals(
            ApplicationSettings.GamepadSelection.Device(unavailableId),
            draft.selections[0],
        )
        assertEquals(
            ApplicationSettings.GamepadSelection.Device(availableId),
            draft.selections[1],
        )
        assertEquals(
            ApplicationSettings.GamepadTuning(
                movementDeadZone = 12_345,
                tiltDeadZone = 2_345,
                invertMovementX = true,
                invertTiltY = true,
            ),
            draft.tunings[availableId],
        )
        assertEquals(
            ApplicationSettings.GamepadTuning(movementDeadZone = 2_048),
            draft.tunings[unavailableId],
        )
        assertEquals("Player 2 gamepad", editor.playerSelector.accessibleContext.accessibleName)
        assertEquals("Movement dead zone", editor.movementDeadZone.accessibleContext.accessibleName)
        assertEquals("Tilt dead zone", editor.tiltDeadZone.accessibleContext.accessibleName)
        assertFalse(editor.advancedTuningPanel.isVisible)
        editor.advancedTuningToggle.doClick()
        assertTrue(editor.advancedTuningPanel.isVisible)
      }

  @Test
  fun `duplicate gamepad assignments mark every conflicting player field`() =
      onEdt {
        val stableId = gamepadId('c')
        val editor =
            GamepadPreferencesEditor(
                ApplicationSettings.Input.defaults(),
                snapshots =
                    GamepadSnapshotProvider {
                      gamepadSnapshot(
                          GamepadCatalog.Status.AVAILABLE,
                          listOf(GamepadCatalog.Device(stableId, "Shared Pad", null)),
                      )
                    },
            )
        val selection = ApplicationSettings.GamepadSelection.Device(stableId)
        selectGamepad(editor, 0, selection)
        selectGamepad(editor, 1, selection)
        selectGamepad(editor, 2, ApplicationSettings.GamepadSelection.Auto)
        selectGamepad(editor, 3, ApplicationSettings.GamepadSelection.Auto)

        val failure =
            assertFailsWith<PreferenceEditorValidationException> {
              editor.validatedDraft()
            }

        assertSame(editor.playerSelector, failure.invalidComponent)
        assertEquals(0, editor.selectedPlayer)
        assertTrue(editor.playerError.text.contains("Player 2"))
        editor.selectPlayer(1)
        assertTrue(editor.playerError.text.contains("Player 1"))
        editor.selectPlayer(2)
        assertTrue(editor.playerError.text.contains("Player 4"))
        editor.selectPlayer(3)
        assertTrue(editor.playerError.text.contains("Player 3"))
      }

  @Test
  fun `unchanged available controller does not create a tuning profile`() =
      onEdt {
        val availableId = gamepadId('e')
        val editor =
            GamepadPreferencesEditor(
                ApplicationSettings.Input.defaults(),
                snapshots =
                    GamepadSnapshotProvider {
                      gamepadSnapshot(
                          GamepadCatalog.Status.AVAILABLE,
                          listOf(GamepadCatalog.Device(availableId, "Untuned Pad", null)),
                      )
                    },
            )

        assertTrue(editor.validatedDraft().tunings.isEmpty())
      }

  @Test
  fun `gamepad tuning profile overflow is translated to its field error`() =
      onEdt {
        val existing =
            (0 until ApplicationSettings.MAX_GAMEPAD_TUNINGS).associate { index ->
              gamepadId(index) to ApplicationSettings.GamepadTuning()
            }
        val extraId = gamepadId(ApplicationSettings.MAX_GAMEPAD_TUNINGS)
        val initial =
            ApplicationSettings.Input.defaults().copy(gamepadTunings = existing)
        val editor =
            GamepadPreferencesEditor(
                initial,
                snapshots =
                    GamepadSnapshotProvider {
                      gamepadSnapshot(
                          GamepadCatalog.Status.AVAILABLE,
                          listOf(GamepadCatalog.Device(extraId, "Extra Pad", null)),
                      )
                    },
            )
        selectTuningDevice(editor, extraId)
        editor.invertTiltX.doClick()

        val failure =
            assertFailsWith<PreferenceEditorValidationException> {
              editor.validatedDraft()
            }

        assertSame(editor.tuningDevice, failure.invalidComponent)
        assertTrue(editor.tuningError.text.contains("At most 32"))
      }

  @Test
  fun `gamepad catalog polling stays on the EDT and its timer stops on disposal`() =
      onEdt {
        val calls = AtomicInteger()
        val allCallsOnEdt = AtomicBoolean(true)
        val editor =
            GamepadPreferencesEditor(
                ApplicationSettings.Input.defaults(),
                snapshots =
                    GamepadSnapshotProvider {
                      calls.incrementAndGet()
                      allCallsOnEdt.compareAndSet(
                          true,
                          SwingUtilities.isEventDispatchThread(),
                      )
                      gamepadSnapshot(GamepadCatalog.Status.AVAILABLE, emptyList())
                    },
            )

        editor.addNotify()
        try {
          assertTrue(editor.isCatalogTimerRunning())
          assertEquals(
              "Refresh game controllers",
              editor.refreshCatalogButton.accessibleContext.accessibleName,
          )
          editor.refreshCatalogButton.doClick()
          assertTrue(calls.get() >= 3)
          assertTrue(allCallsOnEdt.get())
        } finally {
          editor.removeNotify()
        }
        assertFalse(editor.isCatalogTimerRunning())
      }

  @Test
  fun `audio enumeration runs off EDT and retains unavailable configured output`() {
    val configuredId = audioId('a')
    val availableId = audioId('b')
    val providerCalled = CountDownLatch(1)
    val calledOffEdt = AtomicBoolean()
    lateinit var editor: AudioPreferencesEditor
    onEdt {
      editor =
          AudioPreferencesEditor(
              ApplicationSettings.Audio(
                  output = ApplicationSettings.AudioOutputSelection.Device(configuredId)),
              devices =
                  AudioDeviceProvider {
                    calledOffEdt.set(!SwingUtilities.isEventDispatchThread())
                    providerCalled.countDown()
                    listOf(
                        AudioDeviceSnapshot.systemDefaultDevice(),
                        AudioDeviceSnapshot(availableId, "USB Headphones", false),
                    )
                  },
          )
      assertEquals(configuredId, selectedAudioOutput(editor))
      assertTrue(editor.output.selectedItem.toString().contains("Unavailable"))
      editor.startDeviceLoading()
    }
    assertTrue(providerCalled.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    waitForAudioWorker(editor)

    onEdt {
      assertTrue(calledOffEdt.get())
      assertTrue(
          audioOptions(editor).any {
            it.stableId == configuredId && !it.available
          })
      selectAudioOutput(editor, availableId)
      editor.muted.doClick()
      editor.volume.value = 41
      editor.latency.selectedItem = ApplicationSettings.AudioLatency.SAFE

      assertEquals(
          ApplicationSettings.Audio(
              enabled = false,
              output = ApplicationSettings.AudioOutputSelection.Device(availableId),
              volume = 41,
              latency = ApplicationSettings.AudioLatency.SAFE,
          ),
          editor.validatedAudio(),
      )
      assertEquals("Audio output device", editor.output.accessibleContext.accessibleName)
      assertEquals("Master volume", editor.volume.accessibleContext.accessibleName)
      assertEquals(0, editor.volume.minimum)
      assertEquals(100, editor.volume.maximum)
      assertEquals(25, editor.volume.majorTickSpacing)
      assertEquals(5, editor.volume.minorTickSpacing)
      assertTrue(editor.volume.paintTicks)
      assertTrue(editor.volume.paintLabels)
      assertEquals("Audio latency preset", editor.latency.accessibleContext.accessibleName)
    }
  }

  @Test
  fun `cancelled audio worker cannot publish a stale device result`() {
    val staleId = audioId('c')
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val interrupted = AtomicBoolean()
    lateinit var editor: AudioPreferencesEditor
    onEdt {
      editor =
          AudioPreferencesEditor(
              ApplicationSettings.Audio(),
              devices =
                  AudioDeviceProvider {
                    started.countDown()
                    var released = false
                    while (!released) {
                      try {
                        released = release.await(50, TimeUnit.MILLISECONDS)
                      } catch (_: InterruptedException) {
                        interrupted.set(true)
                      }
                    }
                    finished.countDown()
                    listOf(AudioDeviceSnapshot(staleId, "Stale Output", false))
                  },
          )
      editor.startDeviceLoading()
    }
    assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    onEdt {
      editor.cancelDeviceLoading()
      assertFalse(editor.isDeviceLoading())
    }
    release.countDown()
    assertTrue(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    flushSwingWorkerEvents()

    onEdt {
      assertTrue(interrupted.get())
      assertEquals(
          listOf(AudioDeviceSnapshot.SYSTEM_DEFAULT_ID),
          audioOptions(editor).map { it.stableId },
      )
    }
  }

  @Test
  fun `panel defaults do not enumerate host audio and gamepad conflicts select their tab`() {
    lateinit var panel: PreferencesPanel
    onEdt {
      val stableId = gamepadId('d')
      panel =
          PreferencesPanel(
              ApplicationSettings(),
              gamepadSnapshots =
                  GamepadSnapshotProvider {
                    gamepadSnapshot(
                        GamepadCatalog.Status.AVAILABLE,
                        listOf(GamepadCatalog.Device(stableId, "Conflict Pad", null)),
                    )
                  },
          )
      panel.audioEditor.startDeviceLoading()
      selectGamepad(
          panel.gamepadEditor,
          0,
          ApplicationSettings.GamepadSelection.Device(stableId),
      )
      selectGamepad(
          panel.gamepadEditor,
          1,
          ApplicationSettings.GamepadSelection.Device(stableId),
      )
    }
    waitForAudioWorker(panel.audioEditor)

    onEdt {
      assertEquals(
          listOf(AudioDeviceSnapshot.SYSTEM_DEFAULT_ID),
          audioOptions(panel.audioEditor).map { it.stableId },
      )
      var applyCount = 0
      var closeCount = 0
      PreferencesDialogActions(
              panel,
              applyEdit = { applyCount++ },
              close = { closeCount++ },
          )
          .apply()

      assertEquals(0, applyCount)
      assertEquals(0, closeCount)
      assertEquals("Controls", panel.tabs.getTitleAt(panel.tabs.selectedIndex))
      assertTrue(panel.validationSummary.text.contains("multiple players"))
      assertEquals(
          listOf(
              "General",
              "Display",
              "Audio",
              "Controls",
              "Saves & Rewind",
              "System",
              "Peripherals",
          ),
          (0 until panel.tabs.tabCount).map(panel.tabs::getTitleAt),
      )
      assertEquals("Gamepad preferences", panel.gamepadEditor.accessibleContext.accessibleName)
      assertEquals(
          "Peripheral preferences",
          panel.peripheralsEditor.accessibleContext.accessibleName,
      )
      assertEquals("Audio preferences", panel.audioEditor.accessibleContext.accessibleName)
    }
  }

  @Test
  fun `cancel action stops active gamepad polling and audio enumeration`() {
    val started = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val release = CountDownLatch(1)
    var panel: PreferencesPanel? = null
    var closeCount = 0
    try {
      onEdt {
        val activePanel =
            PreferencesPanel(
                ApplicationSettings(),
                audioDevices =
                    AudioDeviceProvider {
                      started.countDown()
                      try {
                        release.await()
                      } catch (_: InterruptedException) {
                        // Cancellation is the expected way out.
                      } finally {
                        finished.countDown()
                      }
                      emptyList()
                    },
            )
        panel = activePanel
        activePanel.gamepadEditor.addNotify()
        activePanel.audioEditor.startDeviceLoading()
        assertTrue(activePanel.gamepadEditor.isCatalogTimerRunning())
        assertTrue(activePanel.audioEditor.isDeviceLoading())
      }
      assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      onEdt {
        val activePanel = checkNotNull(panel)
        PreferencesDialogActions(activePanel, applyEdit = {}, close = { closeCount++ }).cancel()
        assertFalse(activePanel.gamepadEditor.isCatalogTimerRunning())
        assertFalse(activePanel.audioEditor.isDeviceLoading())
        assertEquals(1, closeCount)
      }
      assertTrue(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    } finally {
      release.countDown()
      panel?.let { activePanel ->
        onEdt {
          activePanel.stopBackgroundWork()
          activePanel.gamepadEditor.removeNotify()
        }
      }
    }
  }

  private fun selectGamepad(
      editor: GamepadPreferencesEditor,
      player: Int,
      selection: ApplicationSettings.GamepadSelection,
  ) {
    editor.selectPlayer(player)
    val selector = editor.playerSelector
    selector.selectedItem =
        (0 until selector.itemCount)
            .map(selector::getItemAt)
            .first { it.selection == selection }
  }

  private fun selectedGamepad(
      editor: GamepadPreferencesEditor,
      player: Int,
  ): ApplicationSettings.GamepadSelection = editor.selectionForPlayer(player)

  private fun selectTuningDevice(
      editor: GamepadPreferencesEditor,
      stableId: String,
  ) {
    editor.tuningDevice.selectedItem =
        (0 until editor.tuningDevice.itemCount)
            .map(editor.tuningDevice::getItemAt)
            .first { it.stableId == stableId }
  }

  private fun selectAudioOutput(
      editor: AudioPreferencesEditor,
      stableId: String,
  ) {
    editor.output.selectedItem = audioOptions(editor).first { it.stableId == stableId }
  }

  private fun selectedAudioOutput(editor: AudioPreferencesEditor): String =
      (editor.output.selectedItem as AudioPreferencesEditor.OutputOption).stableId

  private fun audioOptions(
      editor: AudioPreferencesEditor
  ): List<AudioPreferencesEditor.OutputOption> =
      (0 until editor.output.itemCount).map(editor.output::getItemAt)

  private fun gamepadSnapshot(
      status: GamepadCatalog.Status,
      devices: List<GamepadCatalog.Device>,
  ): GamepadCatalog.Snapshot =
      GamepadCatalog.Snapshot(status, devices, "")

  private fun gamepadId(fill: Char): String = "sdl-" + fill.toString().repeat(64)

  private fun gamepadId(index: Int): String =
      "sdl-" + index.toString(16).padStart(64, '0')

  private fun audioId(fill: Char): String = "java-sound-" + fill.toString().repeat(64)

  private fun waitForAudioWorker(editor: AudioPreferencesEditor) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (onEdt { editor.isDeviceLoading() } && System.nanoTime() < deadline) {
      Thread.yield()
    }
    assertFalse(onEdt { editor.isDeviceLoading() }, "Audio enumeration did not finish")
  }

  private fun flushSwingWorkerEvents() {
    repeat(20) {
      Thread.sleep(10)
      onEdt {}
    }
  }

  private fun <T> onEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    val task = FutureTask(action)
    SwingUtilities.invokeAndWait(task)
    return task.get()
  }

  private companion object {
    const val TIMEOUT_SECONDS = 5L
  }
}
