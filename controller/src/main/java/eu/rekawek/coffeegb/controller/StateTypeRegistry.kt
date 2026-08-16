package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.state.StateRecordIntrospection

/**
 * Audited explicit component-state types and Coffee GB 1.7.14 compatibility records. The two
 * class inventories are deliberately disjoint. Newly appended portable types do not acquire
 * compatibility records because no released local snapshot could contain them.
 *
 * IDs are the one-based position in each list for portable StateFile v1. The codec deliberately
 * uses this exact audited inventory so a future record or enum cannot silently become decodable.
 * Entries are append-only for v1; a reorder/removal requires a new StateFile section schema
 * version.
 */
internal object StateTypeRegistry {

  val recordClassNames =
      listOf(
          "eu.rekawek.coffeegb.core.genie.Genie\$GenieState",
          "eu.rekawek.coffeegb.core.sound.FrameSequencer\$FrameSequencerState",
          "eu.rekawek.coffeegb.core.sound.FrequencySweep\$FrequencySweepState",
          "eu.rekawek.coffeegb.core.sound.AbstractSoundMode\$AbstractSoundModeState",
          "eu.rekawek.coffeegb.core.sound.Lfsr\$LfsrState",
          "eu.rekawek.coffeegb.core.sound.PolynomialCounter\$PolynomialCounterState",
          "eu.rekawek.coffeegb.core.sound.VolumeEnvelope\$VolumeEnvelopeState",
          "eu.rekawek.coffeegb.core.sound.SoundMode4\$SoundMode4State",
          "eu.rekawek.coffeegb.core.sound.SoundMode3\$SoundMode3State",
          "eu.rekawek.coffeegb.core.sound.SoundMode2\$SoundMode2State",
          "eu.rekawek.coffeegb.core.sound.SoundMode1\$SoundMode1State",
          "eu.rekawek.coffeegb.core.sound.LengthCounter\$LengthCounterState",
          "eu.rekawek.coffeegb.core.sound.Sound\$SoundState",
          "eu.rekawek.coffeegb.core.timer.Timer\$TimerState",
          "eu.rekawek.coffeegb.core.cpu.SpeedMode\$SpeedModeState",
          "eu.rekawek.coffeegb.core.cpu.InterruptManager\$InterruptManagerState",
          "eu.rekawek.coffeegb.core.cpu.Registers\$RegistersState",
          "eu.rekawek.coffeegb.core.cpu.Cpu\$CpuState",
          "eu.rekawek.coffeegb.core.joypad.Joypad\$JoypadState",
          "eu.rekawek.coffeegb.core.sgb.Commands\$TransferCommand\$TransferCommandState",
          "eu.rekawek.coffeegb.core.sgb.SuperGameboy\$SuperGameboyState",
          "eu.rekawek.coffeegb.core.sgb.SgbDisplay\$SgbDisplayState",
          "eu.rekawek.coffeegb.core.sgb.Background\$BackgroundState",
          "eu.rekawek.coffeegb.core.memory.Hdma\$HdmaState",
          "eu.rekawek.coffeegb.core.memory.GbcRam\$GbcRamState",
          "eu.rekawek.coffeegb.core.memory.BiosShadow\$BiosShadowState",
          "eu.rekawek.coffeegb.core.memory.Ram\$RamState",
          "eu.rekawek.coffeegb.core.memory.Mmu\$MmuState",
          "eu.rekawek.coffeegb.core.memory.cart.battery.MemoryBattery\$MemoryBatteryState",
          "eu.rekawek.coffeegb.core.memory.cart.battery.FileBattery\$FileBatteryState",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc1\$Mbc1State",
          "eu.rekawek.coffeegb.core.memory.cart.type.BungEms\$BungEmsState",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc2\$Mbc2State",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc7Eeprom\$EepromState",
          "eu.rekawek.coffeegb.core.memory.cart.type.BasicRom\$BasicRomState",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc5\$Mbc5State",
          "eu.rekawek.coffeegb.core.memory.cart.type.PocketCamera\$PocketCameraState",
          "eu.rekawek.coffeegb.core.memory.cart.type.BhgosMulticart\$BhgosMulticartState",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc7\$Mbc7State",
          "eu.rekawek.coffeegb.core.memory.cart.type.MakonNtOld2\$MakonNtOld2State",
          "eu.rekawek.coffeegb.core.memory.cart.type.Sintax\$SintaxState",
          "eu.rekawek.coffeegb.core.memory.cart.type.WisdomTree\$WisdomTreeState",
          "eu.rekawek.coffeegb.core.memory.cart.type.Huc3\$Huc3State",
          "eu.rekawek.coffeegb.core.memory.cart.type.DuzMulticart\$DuzMulticartState",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mani32kMulticart\$Mani32kState",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc6\$Mbc6State",
          "eu.rekawek.coffeegb.core.memory.cart.type.Huc1\$Huc1State",
          "eu.rekawek.coffeegb.core.memory.cart.type.SachenMmc\$SachenState",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc3\$Mbc3State",
          "eu.rekawek.coffeegb.core.memory.cart.type.Bbd\$BbdState",
          "eu.rekawek.coffeegb.core.memory.cart.type.SlMulticart\$SlMulticartState",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mmm01\$Mmm01State",
          "eu.rekawek.coffeegb.core.memory.cart.type.Tama5\$Tama5State",
          "eu.rekawek.coffeegb.core.memory.cart.type.Datel\$DatelState",
          "eu.rekawek.coffeegb.core.memory.cart.Cartridge\$CartridgeState",
          "eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock\$RealTimeClockState",
          "eu.rekawek.coffeegb.core.memory.UndocumentedGbcRegisters\$UndocumentedGbcRegistersState",
          "eu.rekawek.coffeegb.core.memory.Dma\$DmaState",
          "eu.rekawek.coffeegb.core.memory.OamEchoRam\$OamEchoRamState",
          "eu.rekawek.coffeegb.core.Gameboy\$GameboyState",
          "eu.rekawek.coffeegb.core.gpu.IntQueue\$IntQueueState",
          "eu.rekawek.coffeegb.core.gpu.GpuRegisterValues\$GpuRegisterValuesState",
          "eu.rekawek.coffeegb.core.gpu.Lcdc\$LcdcState",
          "eu.rekawek.coffeegb.core.gpu.Display\$DisplayState",
          "eu.rekawek.coffeegb.core.gpu.Gpu\$GpuState",
          "eu.rekawek.coffeegb.core.gpu.StatRegister\$StatRegisterState",
          "eu.rekawek.coffeegb.core.gpu.VRamTransfer\$VRamTransferState",
          "eu.rekawek.coffeegb.core.gpu.SpriteFifo\$SpriteFifoState",
          "eu.rekawek.coffeegb.core.gpu.ColorPalette\$ColorPaletteState",
          "eu.rekawek.coffeegb.core.gpu.DmgPixelFifo\$DmgPixelFifoState",
          "eu.rekawek.coffeegb.core.gpu.phase.HBlankPhase\$HBlankPhaseState",
          "eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer\$PixelTransferState",
          "eu.rekawek.coffeegb.core.gpu.phase.OamSearch\$SpritePosition\$SpritePositionState",
          "eu.rekawek.coffeegb.core.gpu.phase.VBlankPhase\$VBlankPhaseState",
          "eu.rekawek.coffeegb.core.gpu.phase.OamSearch\$OamSearchState",
          "eu.rekawek.coffeegb.core.gpu.ColorPixelFifo\$ColorPixelFifoState",
          "eu.rekawek.coffeegb.core.gpu.Fetcher\$FetcherState",
          "eu.rekawek.coffeegb.core.ir.FullChanger\$FullChangerState",
          "eu.rekawek.coffeegb.core.ir.InfraredPort\$InfraredPortState",
          "eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint\$Peer2PeerSerialEndpointState",
          "eu.rekawek.coffeegb.core.serial.GameboyPrinterSerialEndpoint\$PrinterState",
          "eu.rekawek.coffeegb.core.serial.SerialPort\$SerialPortState",
          "eu.rekawek.coffeegb.core.serial.GpsReceiverSerialEndpoint\$GpsReceiverState",
          "eu.rekawek.coffeegb.core.serial.ByteReceivingSerialEndpoint\$ByteReceivingSerialEndpointState",
          "eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint\$BarcodeBoyState",
          "eu.rekawek.coffeegb.core.serial.FourPlayerAdapter\$AdapterState",
          "eu.rekawek.coffeegb.core.rumble.CodeBreakerRumble\$CodeBreakerRumbleState",
          "eu.rekawek.coffeegb.core.genie.Genie\$GameGeniePatchState",
          "eu.rekawek.coffeegb.core.genie.Genie\$GameSharkPatchState",
          "eu.rekawek.coffeegb.core.gpu.Gpu\$PendingPpuWriteState",
          "eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer\$DelayedWindowWriteState",
          "eu.rekawek.coffeegb.core.memory.cart.type.XploderGb\$XploderGbState",
          "eu.rekawek.coffeegb.core.memory.cart.type.Vf001Zook\$Vf001ZookState",
          "eu.rekawek.coffeegb.core.memory.cart.type.Vf001General\$Vf001GeneralState",
          "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine\$MobileAdapterEngineState",
          "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint\$MobileAdapterSerialEndpointState",
          "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine\$MobileAdapterEngineNetworkState",
          "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint\$MobileAdapterSerialEndpointNetworkState",
          "eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint\$MobileAdapterSerialEndpointWireState",
          "eu.rekawek.coffeegb.core.memory.cart.type.Hitek\$HitekState",
          "eu.rekawek.coffeegb.core.memory.cart.type.Gowin\$GowinState",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc5Multicart\$Mbc5MulticartState",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc5Multicart\$LoaderMbc5\$LoaderMbc5State",
      )

  val legacyRecordClassNames =
      listOf(
          "eu.rekawek.coffeegb.core.genie.Genie\$GenieMemento",
          "eu.rekawek.coffeegb.core.sound.FrameSequencer\$FrameSequencerMemento",
          "eu.rekawek.coffeegb.core.sound.FrequencySweep\$FrequencySweepMemento",
          "eu.rekawek.coffeegb.core.sound.AbstractSoundMode\$AbstractSoundModeMemento",
          "eu.rekawek.coffeegb.core.sound.Lfsr\$LfsrMemento",
          "eu.rekawek.coffeegb.core.sound.PolynomialCounter\$PolynomialCounterMemento",
          "eu.rekawek.coffeegb.core.sound.VolumeEnvelope\$VolumeEnvelopeMemento",
          "eu.rekawek.coffeegb.core.sound.SoundMode4\$SoundMode4Memento",
          "eu.rekawek.coffeegb.core.sound.SoundMode3\$SoundMode3Memento",
          "eu.rekawek.coffeegb.core.sound.SoundMode2\$SoundMode2Memento",
          "eu.rekawek.coffeegb.core.sound.SoundMode1\$SoundMode1Memento",
          "eu.rekawek.coffeegb.core.sound.LengthCounter\$LengthCounterMemento",
          "eu.rekawek.coffeegb.core.sound.Sound\$SoundMemento",
          "eu.rekawek.coffeegb.core.timer.Timer\$TimerMemento",
          "eu.rekawek.coffeegb.core.cpu.SpeedMode\$SpeedModeMomento",
          "eu.rekawek.coffeegb.core.cpu.InterruptManager\$InterruptManagerMemento",
          "eu.rekawek.coffeegb.core.cpu.Registers\$RegistersMemento",
          "eu.rekawek.coffeegb.core.cpu.Cpu\$CpuMemento",
          "eu.rekawek.coffeegb.core.joypad.Joypad\$JoypadMemento",
          "eu.rekawek.coffeegb.core.sgb.Commands\$TransferCommand\$TransferCommandMemento",
          "eu.rekawek.coffeegb.core.sgb.SuperGameboy\$SuperGameboyMemento",
          "eu.rekawek.coffeegb.core.sgb.SgbDisplay\$SgbDisplayMemento",
          "eu.rekawek.coffeegb.core.sgb.Background\$BackgroundMemento",
          "eu.rekawek.coffeegb.core.memory.Hdma\$HdmaMemento",
          "eu.rekawek.coffeegb.core.memory.GbcRam\$GbcRamMemento",
          "eu.rekawek.coffeegb.core.memory.BiosShadow\$BiosShadowMemento",
          "eu.rekawek.coffeegb.core.memory.Ram\$RamMemento",
          "eu.rekawek.coffeegb.core.memory.Mmu\$MmuMemento",
          "eu.rekawek.coffeegb.core.memory.cart.battery.MemoryBattery\$MemoryBatteryMemento",
          "eu.rekawek.coffeegb.core.memory.cart.battery.FileBattery\$FileBatteryMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc1\$Mbc1Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.BungEms\$BungEmsMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc2\$Mbc2Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc7Eeprom\$EepromMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.BasicRom\$BasicRomMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc5\$Mbc5Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.PocketCamera\$PocketCameraMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.BhgosMulticart\$BhgosMulticartMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc7\$Mbc7Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.MakonNtOld2\$MakonNtOld2Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Sintax\$SintaxMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.WisdomTree\$WisdomTreeMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Huc3\$Huc3Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.DuzMulticart\$DuzMulticartMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mani32kMulticart\$Mani32kMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc6\$Mbc6Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Huc1\$Huc1Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.SachenMmc\$SachenMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc3\$Mbc3Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Bbd\$BbdMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.SlMulticart\$SlMulticartMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mmm01\$Mmm01Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Tama5\$Tama5Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Datel\$DatelMemento",
          "eu.rekawek.coffeegb.core.memory.cart.Cartridge\$CartridgeMemento",
          "eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock\$RealTimeClockMemento",
          "eu.rekawek.coffeegb.core.memory.UndocumentedGbcRegisters\$UndocumentedGbcRegistersMemento",
          "eu.rekawek.coffeegb.core.memory.Dma\$DmaMemento",
          "eu.rekawek.coffeegb.core.memory.OamEchoRam\$OamEchoRamMemento",
          "eu.rekawek.coffeegb.core.Gameboy\$GameboyMemento",
          "eu.rekawek.coffeegb.core.gpu.IntQueue\$IntQueueMemento",
          "eu.rekawek.coffeegb.core.gpu.GpuRegisterValues\$GpuRegisterValuesMemento",
          "eu.rekawek.coffeegb.core.gpu.Lcdc\$LcdcMemento",
          "eu.rekawek.coffeegb.core.gpu.Display\$DisplayMemento",
          "eu.rekawek.coffeegb.core.gpu.Gpu\$GpuMemento",
          "eu.rekawek.coffeegb.core.gpu.StatRegister\$StatRegisterMemento",
          "eu.rekawek.coffeegb.core.gpu.VRamTransfer\$VRamTransferMemento",
          "eu.rekawek.coffeegb.core.gpu.SpriteFifo\$SpriteFifoMemento",
          "eu.rekawek.coffeegb.core.gpu.ColorPalette\$ColorPaletteMemento",
          "eu.rekawek.coffeegb.core.gpu.DmgPixelFifo\$DmgPixelFifoMemento",
          "eu.rekawek.coffeegb.core.gpu.phase.HBlankPhase\$HBlankPhaseMemento",
          "eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer\$PixelTransferMemento",
          "eu.rekawek.coffeegb.core.gpu.phase.OamSearch\$SpritePosition\$SpritePositionMemento",
          "eu.rekawek.coffeegb.core.gpu.phase.VBlankPhase\$VBlankPhaseMemento",
          "eu.rekawek.coffeegb.core.gpu.phase.OamSearch\$OamSearchMemento",
          "eu.rekawek.coffeegb.core.gpu.ColorPixelFifo\$ColorPixelFifoMemento",
          "eu.rekawek.coffeegb.core.gpu.Fetcher\$FetcherMemento",
          "eu.rekawek.coffeegb.core.ir.FullChanger\$FullChangerMemento",
          "eu.rekawek.coffeegb.core.ir.InfraredPort\$InfraredPortMemento",
          "eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint\$Peer2PeerSerialEndpointMemento",
          "eu.rekawek.coffeegb.core.serial.GameboyPrinterSerialEndpoint\$PrinterMemento",
          "eu.rekawek.coffeegb.core.serial.SerialPort\$SerialPortMemento",
          "eu.rekawek.coffeegb.core.serial.GpsReceiverSerialEndpoint\$GpsReceiverMemento",
          "eu.rekawek.coffeegb.core.serial.ByteReceivingSerialEndpoint\$ByteReceivingSerialEndpointMemento",
          "eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint\$BarcodeBoyMemento",
          "eu.rekawek.coffeegb.core.serial.FourPlayerAdapter\$AdapterMemento",
          "eu.rekawek.coffeegb.core.rumble.CodeBreakerRumble\$CodeBreakerRumbleMemento",
          "eu.rekawek.coffeegb.core.genie.GameGeniePatch",
          "eu.rekawek.coffeegb.core.genie.GameSharkPatch",
          "eu.rekawek.coffeegb.core.gpu.Gpu\$PendingPpuWrite",
          "eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer\$DelayedWindowWrite",
      )

  val enumClassNames =
      listOf(
          "eu.rekawek.coffeegb.core.cpu.Cpu\$State",
          "eu.rekawek.coffeegb.core.cpu.InterruptManager\$InterruptType",
          "eu.rekawek.coffeegb.core.gpu.Mode",
          "eu.rekawek.coffeegb.core.gpu.phase.OamSearch\$State",
          "eu.rekawek.coffeegb.core.memory.Hdma\$CpuRequestArbitration",
          "eu.rekawek.coffeegb.core.memory.Hdma\$HaltHdmaState",
          "eu.rekawek.coffeegb.core.memory.Hdma\$WakeRequestArbitration",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc7Eeprom\$State",
          "eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint\$State",
          "eu.rekawek.coffeegb.core.serial.FourPlayerAdapter\$Phase",
          "eu.rekawek.coffeegb.core.sgb.Commands\$MaskEnCmd\$GameboyScreenMask",
      )

  val legacyApplicationClassNames: Set<String> =
      (legacyRecordClassNames + enumClassNames).toSet()

  val recordClasses: List<Class<*>> by lazy {
    recordClassNames.map(::loadAuditedClass).also { classes ->
      classes.forEach(StateRecordIntrospection::requireConstructible)
    }
  }

  val legacyRecordClasses: List<Class<*>> by lazy {
    legacyRecordClassNames.map(::loadAuditedClass).also { classes ->
      classes.forEach(StateRecordIntrospection::requireConstructible)
    }
  }

  val enumClasses: List<Class<*>> by lazy {
    enumClassNames.map(::loadAuditedClass).also { classes ->
      classes.forEach { require(it.isEnum) { "Audited portable type is no longer an enum: $it" } }
    }
  }

  private fun loadAuditedClass(name: String): Class<*> = Class.forName(name, false, javaClass.classLoader)

  fun isAuditedStateType(type: Class<*>): Boolean =
      type in recordClasses || type in legacyRecordClasses
}
