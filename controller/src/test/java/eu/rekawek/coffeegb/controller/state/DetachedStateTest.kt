package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint
import eu.rekawek.coffeegb.core.serial.ByteReceivingSerialEndpoint
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.nio.file.Paths
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class DetachedStateTest {

  @Test
  fun nontrivialPpuAndDisplayStateRoundTripsAndContinuesDeterministically() {
    session().use { session ->
      repeat(31_337) { session.gameboy.tick() }
      assertNotEquals(0, session.gameboy.gpu.ticksInLine)
      session.heldButtons = setOf(Button.A, Button.RIGHT)

      val captured = session.captureDetachedState()
      val display = captured.machine.record(DISPLAY_MEMENTO)
      val buffer = display.intArray("buffer")
      val lastFrame = display.intArray("lastFrame")
      assertEquals(160 * 144, buffer.size)
      assertEquals(160 * 144, lastFrame.size)

      repeat(4_321) { session.gameboy.tick() }
      val expectedContinuation = session.captureDetachedState().machine
      session.heldButtons = setOf(Button.B)

      session.restoreDetachedState(captured)
      assertEquals(setOf(Button.A, Button.RIGHT), session.heldButtons)
      val restoredDisplay = session.captureDetachedState().machine.record(DISPLAY_MEMENTO)
      assertContentEquals(buffer, restoredDisplay.intArray("buffer"))
      assertContentEquals(lastFrame, restoredDisplay.intArray("lastFrame"))

      repeat(4_321) { session.gameboy.tick() }
      val actualContinuation = session.captureDetachedState().machine
      assertEquals(expectedContinuation, actualContinuation, firstDifference(expectedContinuation.root, actualContinuation.root))
    }
  }

  @Test
  fun doubleSpeedActiveDmaHdmaSerialAndAudioContinueDeterministically() {
    session(configuration(cgbSpeedSwitchRom()).setGameboyType(GameboyType.CGB)).use { session ->
      repeat(140_000) {
        if (session.gameboy.speedMode.speedMode == 1 || session.gameboy.cpu.isSpeedSwitching) {
          session.gameboy.tick()
        }
      }
      assertEquals(2, session.gameboy.speedMode.speedMode)

      val bus = session.gameboy.addressSpace
      bus.setByte(0xff26, 0x80)
      bus.setByte(0xff11, 0x80)
      bus.setByte(0xff12, 0xf3)
      bus.setByte(0xff13, 0x40)
      bus.setByte(0xff14, 0x87)
      bus.setByte(0xff01, 0xa5)
      bus.setByte(0xff02, 0x81)
      bus.setByte(0xff51, 0xc0)
      bus.setByte(0xff52, 0x00)
      bus.setByte(0xff53, 0x00)
      bus.setByte(0xff54, 0x00)
      bus.setByte(0xff55, 0x80)
      bus.setByte(0xff46, 0xc0)

      val captured = session.captureDetachedState()
      assertTrue(captured.machine.record(DMA_MEMENTO).bool("transferInProgress"))
      assertTrue(captured.machine.record(HDMA_MEMENTO).bool("transferInProgress"))
      assertTrue(captured.machine.record(SPEED_MEMENTO).bool("currentSpeed"))
      assertTrue(captured.machine.record(SOUND_MODE_MEMENTO).bool("channelEnabled"))
      assertEquals(0x81, captured.machine.record(SERIAL_PORT_MEMENTO).int("sc"))

      repeat(96) { session.gameboy.tick() }
      val expected = session.captureDetachedState()
      session.restoreDetachedState(captured)
      repeat(96) { session.gameboy.tick() }
      assertEquals(expected, session.captureDetachedState())
    }
  }

  @Test
  fun rootOwnsExactlyOneDisplayAndArraysAreDetachedAcrossCaptures() {
    session().use { session ->
      repeat(8_765) { session.gameboy.tick() }
      val first = session.captureDetachedState()

      assertEquals(1, first.machine.recordCount(DISPLAY_MEMENTO))
      val owned = first.machine.record(DISPLAY_MEMENTO).arrayState("buffer")
      val original = owned.copyValue()
      val escapedCopy = owned.copyValue().also { it[0] = it[0] xor 0x00ffffff }
      assertNotEquals(escapedCopy[0], owned.copyValue()[0])

      repeat(1_111) { session.gameboy.tick() }
      val second = session.captureDetachedState()
      session.restoreDetachedState(first)
      assertContentEquals(original, first.machine.record(DISPLAY_MEMENTO).intArray("buffer"))

      session.restoreDetachedState(second)
      assertEquals(second, session.captureDetachedState())
      assertContentEquals(original, first.machine.record(DISPLAY_MEMENTO).intArray("buffer"))
    }
  }

  @Test
  fun completeGraphIsValidatedBeforeMutationAndFailedApplyIsAtomic() {
    session().use { session ->
      repeat(2_000) { session.gameboy.tick() }
      val before = session.captureDetachedState()
      val invalidRoot = RecordState(before.machine.root.typeId, before.machine.root.fields.dropLast(1))
      val invalid = SessionState(
          MachineState(invalidRoot, before.machine.rtcRuntime),
          before.serialPeripheral,
          before.serialState,
          before.serialRuntime,
          before.heldButtons,
      )

      assertFailsWith<StateApplyException> { session.restoreDetachedState(invalid) }
      assertEquals(before, session.captureDetachedState())
    }
  }

  @Test
  fun statefulEndpointAndHeldInputAreOwnedWhileCallbackIsExcluded() {
    val received = mutableListOf<Int>()
    val endpoint = ByteReceivingSerialEndpoint(received::add)
    session(endpoint).use { session ->
      endpoint.setSb(0xa5)
      endpoint.startSending()
      repeat(3) { endpoint.sendBit() }
      session.heldButtons = setOf(Button.START)
      val captured = session.captureDetachedState()

      repeat(5) { endpoint.sendBit() }
      session.heldButtons = emptySet()
      assertEquals(listOf(0xa5), received)

      session.restoreDetachedState(captured)
      assertEquals(setOf(Button.START), session.heldButtons)
      repeat(5) { endpoint.sendBit() }
      assertEquals(listOf(0xa5, 0xa5), received)
    }
  }

  @Test
  fun barcodePendingPayloadAndExternalTransferLatchAreDetachedAndRestored() {
    val endpoint = BarcodeBoySerialEndpoint()
    session(endpoint).use { session ->
      repeat(32) {
        endpoint.startSending()
        repeat(8) { endpoint.sendBit() }
      }
      endpoint.scan("4901234567894")
      endpoint.setExternalTransfer(true)
      val captured = session.captureDetachedState()

      repeat(512) { endpoint.recvBit() }
      endpoint.setExternalTransfer(false)
      assertTrue(endpoint.isScanPending)

      session.restoreDetachedState(captured)
      assertEquals(captured, session.captureDetachedState())
      assertTrue(endpoint.isScanPending)
      assertTrue((captured.serialRuntime as BarcodeBoyRuntimeState).transferArmed)
    }
  }

  @Test
  fun unknownStatefulPeripheralFailsExplicitlyInsteadOfBeingOmitted() {
    val endpoint = object : SerialEndpoint {
      override fun setSb(sb: Int) = Unit
      override fun recvBit(): Int = -1
      override fun startSending() = Unit
      override fun sendBit(): Int = 1
      override fun saveToMemento() = null
      override fun restoreFromMemento(memento: eu.rekawek.coffeegb.core.memento.Memento<SerialEndpoint>?) = Unit
    }
    session(endpoint).use { session ->
      val failure = assertFailsWith<StateCaptureException> { session.captureDetachedState() }
      assertTrue(failure.message.orEmpty().contains("Unsupported serial endpoint"))
    }
  }

  private fun session(endpoint: SerialEndpoint = SerialEndpoint.NULL_ENDPOINT): Session =
      session(configuration(), endpoint)

  private fun session(
      configuration: Gameboy.GameboyConfiguration,
      endpoint: SerialEndpoint = SerialEndpoint.NULL_ENDPOINT,
  ): Session = Session(configuration, EventBusImpl(), null, endpoint)

  private fun configuration(): Gameboy.GameboyConfiguration =
      Gameboy.GameboyConfiguration(Rom(ROM)).setBootstrapMode(BootstrapMode.SKIP)

  private fun configuration(bytes: ByteArray): Gameboy.GameboyConfiguration =
      Gameboy.GameboyConfiguration(Rom(bytes)).setBootstrapMode(BootstrapMode.SKIP)

  private fun MachineState.record(className: String): RecordState {
    fun find(value: StateValue): RecordState? =
        when (value) {
          is RecordState ->
              if (MementoClassNames.record(value.typeId) == className) value
              else value.fields.firstNotNullOfOrNull { find(it.value) }
          is ObjectArrayState -> value.values.firstNotNullOfOrNull(::find)
          is ListState -> value.values.firstNotNullOfOrNull(::find)
          is Int32MapState -> value.entries.firstNotNullOfOrNull { find(it.value) }
          else -> null
        }
    return requireNotNull(find(root)) { "Missing $className" }
  }

  private fun RecordState.arrayState(name: String): Int32ArrayState =
      fields.single { it.name == name }.value as Int32ArrayState

  private fun RecordState.intArray(name: String): IntArray = arrayState(name).copyValue()

  private fun RecordState.int(name: String): Int =
      (fields.single { it.name == name }.value as Int32State).value

  private fun RecordState.bool(name: String): Boolean =
      (fields.single { it.name == name }.value as BooleanState).value

  private fun firstDifference(expected: StateValue, actual: StateValue, path: String = "root"): String {
    if (expected == actual) return "states are equal"
    if (expected::class != actual::class) return "$path: ${expected::class} != ${actual::class}"
    return when {
      expected is RecordState && actual is RecordState ->
          expected.fields.indices.firstNotNullOfOrNull { index ->
            val left = expected.fields[index]
            val right = actual.fields.getOrNull(index) ?: return@firstNotNullOfOrNull "$path: missing field"
            if (left.value == right.value) null else firstDifference(left.value, right.value, "$path.${left.name}")
          } ?: "$path: record metadata differs"
      expected is ObjectArrayState && actual is ObjectArrayState ->
          expected.values.indices.firstNotNullOfOrNull { index ->
            if (expected.values[index] == actual.values[index]) null
            else firstDifference(expected.values[index], actual.values[index], "$path[$index]")
          } ?: "$path: array metadata differs"
      expected is ListState && actual is ListState ->
          expected.values.indices.firstNotNullOfOrNull { index ->
            if (expected.values[index] == actual.values[index]) null
            else firstDifference(expected.values[index], actual.values[index], "$path[$index]")
          } ?: "$path: list metadata differs"
      else -> "$path: $expected != $actual"
    }
  }

  private object MementoClassNames {
    fun record(typeId: Int): String =
        eu.rekawek.coffeegb.controller.MementoTypeRegistry.recordClasses[typeId - 1].name
  }

  private fun cgbSpeedSwitchRom(): ByteArray {
    val rom = ByteArray(0x8000)
    rom[0x100] = 0x3e
    rom[0x101] = 0x01
    rom[0x102] = 0xe0.toByte()
    rom[0x103] = 0x4d
    rom[0x104] = 0x10
    rom[0x105] = 0x00
    rom[0x106] = 0x18
    rom[0x107] = 0xfe.toByte()
    rom[0x143] = 0x80.toByte()
    return rom
  }

  private companion object {
    const val DISPLAY_MEMENTO = "eu.rekawek.coffeegb.core.gpu.Display\$DisplayMemento"
    const val DMA_MEMENTO = "eu.rekawek.coffeegb.core.memory.Dma\$DmaMemento"
    const val HDMA_MEMENTO = "eu.rekawek.coffeegb.core.memory.Hdma\$HdmaMemento"
    const val SPEED_MEMENTO = "eu.rekawek.coffeegb.core.cpu.SpeedMode\$SpeedModeMomento"
    const val SOUND_MODE_MEMENTO = "eu.rekawek.coffeegb.core.sound.AbstractSoundMode\$AbstractSoundModeMemento"
    const val SERIAL_PORT_MEMENTO = "eu.rekawek.coffeegb.core.serial.SerialPort\$SerialPortMemento"
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()
  }
}
