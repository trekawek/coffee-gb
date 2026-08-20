package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.core.ExecutionMode
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyEvent
import java.nio.file.Paths
import java.util.concurrent.FutureTask
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.junit.Test

class SystemPreferencesEditorTest {

  @Test
  fun `visible system controls produce one advanced draft and preserve hidden fields`() =
      onEdt {
        val slotRom = Paths.get("devices", "datel-slot.gb")
        val initial =
            ApplicationSettings.Advanced(
                dmgGamesProfile = explicit(HardwareProfileRegistry.DMG),
                cgbGamesProfile = explicit(HardwareProfileRegistry.CGB),
                bootstrapMode = BootstrapMode.SKIP,
                executionMode = ExecutionMode.PERFORMANCE,
                datelSlotRom = slotRom,
                fullChangerCharacter = "MARIO",
            )
        val editor = SystemPreferencesEditor(initial)

        selectProfile(editor.dmgGamesProfile, explicit(HardwareProfileRegistry.SGB2))
        selectProfile(editor.cgbGamesProfile, ApplicationSettings.ProfileSelection.Auto)
        selectBootstrap(editor, BootstrapMode.NORMAL)
        selectExecutionMode(editor, ExecutionMode.ACCURACY)

        assertEquals(
            ApplicationSettings.Advanced(
                dmgGamesProfile = explicit(HardwareProfileRegistry.SGB2),
                cgbGamesProfile = ApplicationSettings.ProfileSelection.Auto,
                bootstrapMode = BootstrapMode.NORMAL,
                executionMode = ExecutionMode.ACCURACY,
                datelSlotRom = slotRom,
                fullChangerCharacter = "MARIO",
            ),
            editor.validatedAdvanced(),
        )
      }

  @Test
  fun `profile controls contain Auto followed by every supported hardware profile`() =
      onEdt {
        val editor = SystemPreferencesEditor(ApplicationSettings.Advanced())

        for (control in listOf(editor.dmgGamesProfile, editor.cgbGamesProfile)) {
          val options = (0 until control.itemCount).map(control::getItemAt)
          assertEquals("Auto (default)", options.first().label)
          assertEquals(ApplicationSettings.ProfileSelection.Auto, options.first().selection)
          assertEquals(
              HardwareProfileRegistry.supportedProfiles(),
              options.drop(1).map {
                (it.selection as ApplicationSettings.ProfileSelection.Explicit).profile
              },
          )
        }
      }

  @Test
  fun `restore defaults resets exposed fields without changing hidden initial values`() =
      onEdt {
        val initialSlotRom = Paths.get("devices", "original-slot.gb")
        val initial =
            ApplicationSettings.Advanced(
                dmgGamesProfile = explicit(HardwareProfileRegistry.MGB),
                cgbGamesProfile = explicit(HardwareProfileRegistry.CGB0),
                bootstrapMode = BootstrapMode.NORMAL,
                datelSlotRom = initialSlotRom,
                fullChangerCharacter = "LUIGI",
            )
        val defaults =
            ApplicationSettings.Advanced(
                dmgGamesProfile = ApplicationSettings.ProfileSelection.Auto,
                cgbGamesProfile = explicit(HardwareProfileRegistry.SGB),
                bootstrapMode = BootstrapMode.FAST_FORWARD,
                datelSlotRom = Paths.get("must-not-replace-hidden.gb"),
                fullChangerCharacter = "HIDDEN DEFAULT",
            )
        val editor = SystemPreferencesEditor(initial, defaults)

        editor.restoreDefaults()

        assertEquals(
            defaults.copy(
                datelSlotRom = initialSlotRom,
                fullChangerCharacter = "LUIGI",
            ),
            editor.validatedAdvanced(),
        )
      }

  @Test
  fun `bootstrap control keeps the System menu labels and values`() =
      onEdt {
        val editor = SystemPreferencesEditor(ApplicationSettings.Advanced())
        val options =
            (0 until editor.bootstrapMode.itemCount).map(editor.bootstrapMode::getItemAt)

        assertEquals(
            listOf(
                BootstrapMode.SKIP to "Skip",
                BootstrapMode.FAST_FORWARD to "Fast-forward",
                BootstrapMode.NORMAL to "Full",
            ),
            options.map { it.mode to it.label },
        )
      }

  @Test
  fun `execution mode control exposes the reference default and guarded performance option`() =
      onEdt {
        val editor =
            SystemPreferencesEditor(
                ApplicationSettings.Advanced(executionMode = ExecutionMode.PERFORMANCE))
        val options =
            (0 until editor.executionMode.itemCount).map(editor.executionMode::getItemAt)

        assertEquals(
            listOf(
                ExecutionMode.ACCURACY to "Accuracy (cycle/dot-accurate reference)",
                ExecutionMode.PERFORMANCE to "Performance (guarded batching)",
            ),
            options.map { it.mode to it.label },
        )
        assertEquals(ExecutionMode.PERFORMANCE, editor.validatedAdvanced().executionMode)
      }

  @Test
  fun `system controls have accessible names label associations and mnemonics`() =
      onEdt {
        val editor = SystemPreferencesEditor(ApplicationSettings.Advanced())
        val labels = descendants(editor).filterIsInstance<JLabel>().toList()

        assertSame(
            editor.dmgGamesProfile,
            labels.single { it.text == "DMG games:" }.labelFor,
        )
        assertSame(
            editor.cgbGamesProfile,
            labels.single { it.text == "CGB games:" }.labelFor,
        )
        assertSame(
            editor.bootstrapMode,
            labels.single { it.text == "Bootstrap:" }.labelFor,
        )
        assertSame(
            editor.executionMode,
            labels.single { it.text == "Execution mode:" }.labelFor,
        )
        assertEquals(
            KeyEvent.VK_D,
            labels.single { it.text == "DMG games:" }.displayedMnemonic,
        )
        assertEquals(
            KeyEvent.VK_C,
            labels.single { it.text == "CGB games:" }.displayedMnemonic,
        )
        assertEquals(
            KeyEvent.VK_B,
            labels.single { it.text == "Bootstrap:" }.displayedMnemonic,
        )
        assertEquals(
            KeyEvent.VK_E,
            labels.single { it.text == "Execution mode:" }.displayedMnemonic,
        )
        assertEquals(
            "DMG game hardware profile",
            editor.dmgGamesProfile.accessibleContext.accessibleName,
        )
        assertEquals(
            "CGB game hardware profile",
            editor.cgbGamesProfile.accessibleContext.accessibleName,
        )
        assertEquals(
            "Bootstrap mode",
            editor.bootstrapMode.accessibleContext.accessibleName,
        )
        assertEquals(
            "Execution mode",
            editor.executionMode.accessibleContext.accessibleName,
        )
      }

  @Test
  fun `system editor rejects construction outside the Event Dispatch Thread`() {
    assertFailsWith<IllegalStateException> {
      SystemPreferencesEditor(ApplicationSettings.Advanced())
    }
  }

  private fun selectProfile(
      control: JComboBox<SystemPreferencesEditor.ProfileOption>,
      selection: ApplicationSettings.ProfileSelection,
  ) {
    control.selectedItem =
        (0 until control.itemCount)
            .map(control::getItemAt)
            .single { it.selection == selection }
  }

  private fun selectBootstrap(
      editor: SystemPreferencesEditor,
      mode: BootstrapMode,
  ) {
    editor.bootstrapMode.selectedItem =
        (0 until editor.bootstrapMode.itemCount)
            .map(editor.bootstrapMode::getItemAt)
            .single { it.mode == mode }
  }

  private fun selectExecutionMode(
      editor: SystemPreferencesEditor,
      mode: ExecutionMode,
  ) {
    editor.executionMode.selectedItem =
        (0 until editor.executionMode.itemCount)
            .map(editor.executionMode::getItemAt)
            .single { it.mode == mode }
  }

  private fun explicit(
      profile: HardwareProfile,
  ) = ApplicationSettings.ProfileSelection.Explicit(profile)

  private fun descendants(component: Component): Sequence<Component> =
      sequence {
        yield(component)
        if (component is Container) {
          component.components.forEach { yieldAll(descendants(it)) }
        }
      }

  private fun <T> onEdt(action: () -> T): T {
    val task = FutureTask(action)
    SwingUtilities.invokeAndWait(task)
    return task.get()
  }
}
