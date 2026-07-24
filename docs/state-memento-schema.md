# Audited state-memento schema

This is the exact captured-field inventory for all 91 production record types admitted by
`MementoTypeRegistry` at Phase 1. Each line names the concrete record and every record component
captured by `saveToMemento`. The executable state inventory test prevents the registry, this
appendix, and the detached graph adapter from drifting independently.

Array and collection fields are deep-owned by the detached state adapter. The ownership contract,
safe points, derived/excluded state, and subsystem grouping are in
[state-machine-inventory.md](state-machine-inventory.md).

The 11 explicit behavior tags are `Cpu.State`, `InterruptManager.InterruptType`, `Gpu.Mode`,
`OamSearch.State`, `Hdma.CpuRequestArbitration`, `Hdma.HaltHdmaState`,
`Hdma.WakeRequestArbitration`, `Mbc7Eeprom.State`, `BarcodeBoySerialEndpoint.State`,
`FourPlayerAdapter.Phase`, and `Commands.MaskEnCmd.GameboyScreenMask`. Their portable form is the
audited enum type ID plus ordinal; a missing type or out-of-range ordinal is rejected.

`DmgPixelFifo$DmgPixelFifoMemento` deliberately retains its pinned seven-component Java
serialization descriptor. The immutable Phase-1 `MachineState` carries `linePixels`, `outCount`,
`firstEntry`, `firstBgp`, `firstObp0`, and `firstObp1` for both DMG dot machines in the separate
primitive-only `DmgFifoRuntimeState` supplement. This closes current state ownership without
altering the 1.7.13/1.7.14 migration schema listed below.

- `eu.rekawek.coffeegb.core.genie.Genie$GenieMemento`: patches
- `eu.rekawek.coffeegb.core.sound.FrameSequencer$FrameSequencerMemento`: step, previousBit, skipNextEdge
- `eu.rekawek.coffeegb.core.sound.FrequencySweep$FrequencySweepMemento`: period, negate, shift, timer, shadowFreq, nr13, nr14, overflow, counterEnabled, negging, calculationDelay, unshiftedCalculation, restartHold, frequencyUpdatePending
- `eu.rekawek.coffeegb.core.sound.AbstractSoundMode$AbstractSoundModeMemento`: channelEnabled, dacEnabled, nr0, nr1, nr2, nr3, nr4, lengthMemento
- `eu.rekawek.coffeegb.core.sound.Lfsr$LfsrMemento`: lfsr
- `eu.rekawek.coffeegb.core.sound.PolynomialCounter$PolynomialCounterMemento`: nr43, counter, counterCountdown, clock2Mhz, alignment, backgroundActive, countdownReloaded
- `eu.rekawek.coffeegb.core.sound.VolumeEnvelope$VolumeEnvelopeMemento`: initialVolume, envelopeDirection, sweep, volume, timer, finished, pendingEnvelopeClock
- `eu.rekawek.coffeegb.core.sound.SoundMode4$SoundMode4Memento`: abstractSoundMemento, volumeEnvelopeMemento, polynomialCounterMemento, lastResult, lfsrMemento
- `eu.rekawek.coffeegb.core.sound.SoundMode3$SoundMode3Memento`: abstractSoundMemento, waveRamMemento, freqDivider, lastOutput, i, ticksSinceRead, lastReadAddr, buffer, triggered, clock2Mhz
- `eu.rekawek.coffeegb.core.sound.SoundMode2$SoundMode2Memento`: abstractSoundMemento, freqDivider, lastOutput, i, sampleSuppressed, activeBeforeTrigger, clock2Mhz, lowFrequencyPhase, volumeEnvelopeMemento, justReloadedTicks
- `eu.rekawek.coffeegb.core.sound.SoundMode1$SoundMode1Memento`: abstractSoundMemento, freqDivider, lastOutput, i, sampleSuppressed, activeBeforeTrigger, clock2Mhz, lowFrequencyPhase, frequencySweepMemento, justReloadedTicks, justReloadedFromSweep, volumeEnvelopeMemento
- `eu.rekawek.coffeegb.core.sound.LengthCounter$LengthCounterMemento`: length, enabled
- `eu.rekawek.coffeegb.core.sound.Sound$SoundMemento`: allModeMementos, ramMemento, frameSequencerMemento, channels, enabled, overriddenEnabled, buffer, i, pendingFrameSequencerStep, frameSequencerClockPhase, frameSequencerDivOffset
- `eu.rekawek.coffeegb.core.timer.Timer$TimerMemento`: div, tac, tma, tima, previousBit, overflow, ticksSinceOverflow, divReset, haltWakeDelay, ticksSinceDivReset, haltBugDivRipplePending, haltBugDivRippleVisible, suppressNextInterruptRequest
- `eu.rekawek.coffeegb.core.cpu.SpeedMode$SpeedModeMomento`: currentSpeed, prepareSpeedSwitch, dmgCompat
- `eu.rekawek.coffeegb.core.cpu.InterruptManager$InterruptManagerMemento`: ime, interruptFlag, interruptEnabled, pendingEnableInterrupts, haltBlockedInterrupts, cpuBlockedInterrupts, cpuPhasedPpuInterrupts, cpuPhasedMode2Interrupts, cpuFirstLineMode2Interrupts, cpuInstructionBlockedInterrupts, maskVBlankOnNextRead, maskLcdcUntilNextPeripheralTick, maskMode0LcdcReadTicks, cpuReadInterruptPreview, serialInterruptAcknowledge, timerInterruptAcknowledge, lcdcInterruptAcknowledge, vBlankInterruptAcknowledge, lcdcInterruptFlagWriteClear
- `eu.rekawek.coffeegb.core.cpu.Registers$RegistersMemento`: a, b, c, d, e, h, l, sp, pc, flags
- `eu.rekawek.coffeegb.core.cpu.Cpu$CpuMemento`: registersMemento, opcode1, opcode2, operand, operandIndex, opIndex, state, opContext, interruptFlag, interruptEnabled, requestedIrq, clockCycle, haltBugMode, haltEntrySampleTicks, synchronousHaltEntryStatPhase, asynchronousHaltEntryStatPhase, ordinaryHaltWakeStatPhase, haltedCpuCycles, hdmaOpcodePrefetched, hdmaArbitrationOpcode, hdmaArbitrationOpcodeValid, haltPrefetchedOpcode, haltOpcodePrefetchValid, speedSwitchPaddingOpcode, speedSwitchPaddingReplayValid, speedSwitchTicks, phasedPpuInputHigh, fastPhasedPpuDispatch
- `eu.rekawek.coffeegb.core.joypad.Joypad$JoypadMemento`: p1, tick, inputHistory, filteredInputLines, inputChangedSinceLastTick, players, currentPlayer, transferInProgress, transferReadyForData, pendingTransferBit, currentByte, currentPacket, currentByteIndex, currentPacketIndex
- `eu.rekawek.coffeegb.core.sgb.Commands$TransferCommand$TransferCommandMemento`: packet, dataTransfer
- `eu.rekawek.coffeegb.core.sgb.SuperGameboy$SuperGameboyMemento`: multipacket, multipacketIndex, multipacketLength, transferCountdown, waitingTransferCommandMemento
- `eu.rekawek.coffeegb.core.sgb.SgbDisplay$SgbDisplayMemento`: sgbBuffer, sgbMask, palettes, systemPalettes, paletteMap, attributeFiles, screenMask, borderFade
- `eu.rekawek.coffeegb.core.sgb.Background$BackgroundMemento`: tiles, pendingPictureMemento, borderAnimation
- `eu.rekawek.coffeegb.core.memory.Hdma$HdmaMemento`: gpuMode, transferInProgress, hblankTransfer, lcdEnabled, length, src, dst, tick, blockData, hblankRequestTicks, hblankRequestAge, nextHblankRequestTicks, nextHblankRequestAge, sourceBytesTransferred, cpuBusValue, stopAfterCurrentBlock, preserveLengthAfterCurrentBlock, speedSwitchInProgress, speedSwitchStartedWithoutRequest, pauseOamDmaForSpeedSwitchBurst, wakeRequestArbitration, gpuLine, gpuTicksInLine, gpuCpuClockRephased, hblankStartTicksInLine, cpuHalted, haltHdmaState, haltEnteredThisTick, requestOverlappedCpuWrite, interruptEntryWonArbitration, cpuRequestArbitration, cpuRequestAllowsLateInterrupt, haltOpcodeRequestLatched
- `eu.rekawek.coffeegb.core.memory.GbcRam$GbcRamMemento`: ram, svbk
- `eu.rekawek.coffeegb.core.memory.BiosShadow$BiosShadowMemento`: isEnabled
- `eu.rekawek.coffeegb.core.memory.Ram$RamMemento`: space
- `eu.rekawek.coffeegb.core.memory.Mmu$MmuMemento`: ramC000Memento, ramD000Memento, ramFF80Memento, gbcRamMemento, undocumentedGbcRegistersMemento, oamEchoRamMemento
- `eu.rekawek.coffeegb.core.memory.cart.battery.MemoryBattery$MemoryBatteryMemento`: buffer
- `eu.rekawek.coffeegb.core.memory.cart.battery.FileBattery$FileBatteryMemento`: clockBuffer, ramBuffer, isClockPresent, isDirty
- `eu.rekawek.coffeegb.core.memory.cart.type.Mbc1$Mbc1Memento`: batteryMemento, ram, selectedRamBank, selectedRomBank, memoryModel, ramWriteEnabled, cachedRomBankFor0x0000, cachedRomBankFor0x4000, ramUpdated, wideBank, upperRegisterUsed
- `eu.rekawek.coffeegb.core.memory.cart.type.BungEms$BungEmsMemento`: batteryMemento, ram, romBankLow, romBankHigh, romBankMask, romBankLatch, selectedRamBank, configureMode, ramEnabled, ramUpdated
- `eu.rekawek.coffeegb.core.memory.cart.type.Mbc2$Mbc2Memento`: batteryMemento, ram, selectedRomBank, ramWriteEnabled, ramUpdated
- `eu.rekawek.coffeegb.core.memory.cart.type.Mbc7Eeprom$EepromMemento`: eeprom, state, bitsRead, command, address, writeValue, writeEnabled, sk, doBit
- `eu.rekawek.coffeegb.core.memory.cart.type.BasicRom$BasicRomMemento`: batteryMemento, ram, ramUpdated
- `eu.rekawek.coffeegb.core.memory.cart.type.Mbc5$Mbc5Memento`: batteryMemento, ram, selectedRamBank, selectedRomBank, ramWriteEnabled, ramUpdated, motorOn
- `eu.rekawek.coffeegb.core.memory.cart.type.PocketCamera$PocketCameraMemento`: ram, cameraRegisters, romBank, ramBank, ramEnabled, cameraMapped
- `eu.rekawek.coffeegb.core.memory.cart.type.BhgosMulticart$BhgosMulticartMemento`: batteryMemento, ram, selectedRomBank, selectedRamBank, baseRomBank, blockSelectWrites, ramUpdated
- `eu.rekawek.coffeegb.core.memory.cart.type.Mbc7$Mbc7Memento`: selectedRomBank, ramWriteEnabled1, ramWriteEnabled2, x, y, latchX, latchY, latchState, eepromMemento
- `eu.rekawek.coffeegb.core.memory.cart.type.MakonNtOld2$MakonNtOld2Memento`: batteryMemento, ram, selectedRomBank, mappedRomBank, baseRomBank, gameRomBankMask, weirdMode, rumbleEnabled, motorOn, ramUpdated
- `eu.rekawek.coffeegb.core.memory.cart.type.Sintax$SintaxMemento`: delegateMemento, xorValues, mode, bankNo, romBankXor
- `eu.rekawek.coffeegb.core.memory.cart.type.WisdomTree$WisdomTreeMemento`: bank
- `eu.rekawek.coffeegb.core.memory.cart.type.Huc3$Huc3Memento`: batteryMemento, ram, romBank, ramBank, mode, minutes, days, alarmMinutes, alarmDays, alarmEnabled, accessIndex, accessFlags, readValue, lastRtcSecond, ramUpdated
- `eu.rekawek.coffeegb.core.memory.cart.type.DuzMulticart$DuzMulticartMemento`: ram, regs, selectedBank, selectedRamBank, baseBank, regIndex, ramWriteEnabled
- `eu.rekawek.coffeegb.core.memory.cart.type.Mani32kMulticart$Mani32kMemento`: block
- `eu.rekawek.coffeegb.core.memory.cart.type.Mbc6$Mbc6Memento`: ram, flash, ramEnabled, ramBankA, ramBankB, romBankA, romBankB, romBankAFlash, romBankBFlash, flashEnabled, flashWriteEnable, flashCommandState, flashIdMode, flashProgramMode
- `eu.rekawek.coffeegb.core.memory.cart.type.Huc1$Huc1Memento`: batteryMemento, ram, romBank, ramBank, irMode, ramUpdated
- `eu.rekawek.coffeegb.core.memory.cart.type.SachenMmc$SachenMemento`: unmaskedBank, mask, base, lockState, transition, serveBootLogo
- `eu.rekawek.coffeegb.core.memory.cart.type.Mbc3$Mbc3Memento`: ram, clockMemento, batteryMemento, selectedRamBank, selectedRomBank, ramEnabled
- `eu.rekawek.coffeegb.core.memory.cart.type.Bbd$BbdMemento`: delegateMemento, dataSwapMode, bankSwapMode
- `eu.rekawek.coffeegb.core.memory.cart.type.SlMulticart$SlMulticartMemento`: ram, configCommand, baseRomBank, selectedRomBank, romBankMask, zeroRemap, configurationMode, mbc5Mode, ramAllowed, ramEnabled, baseRamBank, selectedRamBank, ramBankMask
- `eu.rekawek.coffeegb.core.memory.cart.type.Mmm01$Mmm01Memento`: batteryMemento, ram, ramEnabled, romBankLow, romBankMid, romBankHigh, romBankMask, ramBankLow, ramBankHigh, ramBankMask, locked, mbc1Mode, mbc1ModeDisable, multiplexMode, ramUpdated
- `eu.rekawek.coffeegb.core.memory.cart.type.Tama5$Tama5Memento`: batteryMemento, ram, selectedReg, registers, rtcTimerPage, rtcAlarmPage, rtcFreePage0, rtcFreePage1, rtcDisabled, lastRtcSecond, ramUpdated
- `eu.rekawek.coffeegb.core.memory.cart.type.Datel$DatelMemento`: ram, regs, ramWritten, regsB, flash, flashCycle, flashErasePending, flashIdMode, launched, slotMemento
- `eu.rekawek.coffeegb.core.memory.cart.Cartridge$CartridgeMemento`: memoryControllerMemento, batteryMemento
- `eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock$RealTimeClockMemento`: seconds, minutes, hours, days, halt, counterOverflow, subSecondTicks, latched, latchedSeconds, latchedMinutes, latchedHours, latchedDays, latchedHalt, latchedCounterOverflow
- `eu.rekawek.coffeegb.core.memory.UndocumentedGbcRegisters$UndocumentedGbcRegistersMemento`: ramMemento, xff6c
- `eu.rekawek.coffeegb.core.memory.Dma$DmaMemento`: transferInProgress, restarted, from, ticks, transferClocks, oamOwnedForPpuBeforeTick, oamOwnedForPpu, ppuOamOwnedThroughRestart, cpuClockPaused, pauseEntryClocks, currentByte, regValue, pendingInterruptWriteByte, pendingInterruptWriteValue, vramDmaBusCollisionObserved
- `eu.rekawek.coffeegb.core.memory.OamEchoRam$OamEchoRamMemento`: ram
- `eu.rekawek.coffeegb.core.Gameboy$GameboyMemento`: biosShadowMemento, cartridgeMemento, gpuMemento, statRegisterMemento, mmuMemento, oamRamMemento, cpuMemento, interruptManagerMemento, timerMemento, dmaMemento, hdmaMemento, displayMemento, soundMemento, serialPortMemento, infraredPortMemento, codeBreakerRumbleMemento, joypadMemento, speedModeMemento, superGameboyMemento, backgroundMemento, vRamTransferMemento, sgbDisplayMemento, genieMemento, requestScreenRefresh, lcdDisabled, lcdOffTicks, speedSwitchTailTicks, speedSwitchClockPhaseShifted, blankCgbBootTilePending, clearBootTilemapPending, clearCgbBootOamShadowPending
- `eu.rekawek.coffeegb.core.gpu.IntQueue$IntQueueMemento`: array, size, offset
- `eu.rekawek.coffeegb.core.gpu.GpuRegisterValues$GpuRegisterValuesMemento`: values, mixValues, pendingMixValues, wxJustChangedTicks, scxOldValue, pendingScxOldValue
- `eu.rekawek.coffeegb.core.gpu.Lcdc$LcdcMemento`: value, mixValue, pendingMixValue, dmgBlobBackgroundEnable, pendingDmgBlobBackgroundEnable, tileSelectGlitchTicks, pendingTileSelectGlitchTicks, tileSelectGlitchHistory, oamSizeHistory
- `eu.rekawek.coffeegb.core.gpu.Display$DisplayMemento`: buffer, i, enabled, lastFrame, firstFrameAfterLcdEnable
- `eu.rekawek.coffeegb.core.gpu.Gpu$GpuMemento`: videoRam0Memento, videoRam1Memento, displayMemento, lcdcMemento, bgPaletteMemento, oamPaletteMemento, oamSearchPhaseMemento, pixelTransferPhaseMemento, pixelMachineMemento, rMemento, lcdEnabled, displayEnabledDelay, line, ticksInLine, firstLine, lcdEnableClockPhase, firstFrameAfterLcdEnable, pixelTransferDone, hblankIntFrom, mode0IntFrom, statModeLatchRephasedBySpeedSwitch, speedSwitchCompletedThisLine, lyReadLatchRephasedBySpeedSwitch, scxWrittenThisLine, doubleSpeedMode2DispatchStatTailThisLine, doubleSpeedMode2DispatchCrossedLineEdge, earlyScxStatTailThisLine, wyWrittenThisLine, lateDoubleSpeedLineZeroWindowEnable, lastCpuVramWriteTick, mode, pendingPpuWrites, cpuVisiblePpuRegisters
- `eu.rekawek.coffeegb.core.gpu.StatRegister$StatRegisterMemento`: enableBits, registeredLy, coincidence, intCoincidence, intLine, lycWriteSuppressed, suppressedLycIrqLine, modeBlockedLycIrqLine, lycIrqStatSource, lycIrqValueSource, lycIrqStatLatch, lycIrqValueLatch, lycIrqClock, nextLycIrqEvent, pendingLycWriteIrq, pendingLycComparatorIrq, lastLycIrqRegisterChangeClock, lastLcdcInterruptAcknowledgeClock, lastVBlankInterruptAcknowledgeClock, releaseTailLycCpuAcceptance, lycComparatorSignal, modeIrqStatLatch, modeIrqLycLatch, pendingModeIrqStat, pendingModeIrqLyc, pendingModeIrqStatClock, pendingModeIrqLycClock, mode0IrqStatLatch, mode0IrqLycLatch, pendingMode0IrqStat, pendingMode0IrqLyc, pendingMode0IrqStatClock, pendingMode0IrqLycClock, lastModeIrqStatWriteClock, lastModeIrqStatWriteLineTick, lastModeIrqStatWriteOld, cgbMode1IfClearAtCapture, pendingCgbMode1Interrupt, dmgLyc143Mode1CaptureClock, mode0EventArmed, previousMode0Window, previousMode1Window, previousMode2Window, pendingCgbMode0Interrupt, pendingCgbMode2Interrupt, pendingCgbMode2IfHighAtCapture, pendingCgbMode2LateReplay, pendingCgbMode2PublicationClock, cgbMode2CapturedAtLineEdge, pendingCgbFrameMode2Interrupt, retractableCgbMode2Interrupt, ordinaryHaltWakeStatClock, previousOrdinaryHaltWakePhase, scxChangedSinceMode0Event
- `eu.rekawek.coffeegb.core.gpu.VRamTransfer$VRamTransferMemento`: buffer, i
- `eu.rekawek.coffeegb.core.gpu.SpriteFifo$SpriteFifoMemento`: pixel, palette, priority, bgPriority, head, size, underflow
- `eu.rekawek.coffeegb.core.gpu.ColorPalette$ColorPaletteMemento`: palettes, index, autoIncrement
- `eu.rekawek.coffeegb.core.gpu.DmgPixelFifo$DmgPixelFifoMemento`: pixels, spriteFifo, delayEntry, delayStamp, delayHead, delaySize, outputTicks
- `eu.rekawek.coffeegb.core.gpu.phase.HBlankPhase$HBlankPhaseMemento`: ticks
- `eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer$PixelTransferMemento`: fetcherMemento, fifoMemento, entryTicks, lcdEnableFirstLine, position, window, windowBeingFetched, windowLineCounter, spriteOrder, spriteCount, spriteHead, objStep, objTileId, objAttributesValue, objData0, objTileLine, objData1Pending, objRefreshAge, objRefreshPops, objRefreshD0, objRefreshTileId, objRefreshLine, objRefreshAttrsValue, objRefreshZip, objWaiting, objectTimingPenalty, previousScx, fineScxRephasedThisLine, machineActive, windowPendingTicks, windowPendingWx, windowPendingPos, windowActivatedThisLine, previousWindowDisplay, cgbWindowStartTicks, cgbTerminalWindowStartedThisLine, insertBgPixel, machineStall, windowYTriggered, windowWy, pendingWindowWy, windowWyDelay, windowWyOldOnWriteTick, windowDisplayOverride, pendingWindowDisplayWrites, windowXOverride, pendingWindowXWrites
- `eu.rekawek.coffeegb.core.gpu.phase.OamSearch$SpritePosition$SpritePositionMemento`: x, y, address, enabled
- `eu.rekawek.coffeegb.core.gpu.phase.VBlankPhase$VBlankPhaseMemento`: ticks
- `eu.rekawek.coffeegb.core.gpu.phase.OamSearch$OamSearchMemento`: sprites, oamReaderY, oamReaderX, oamReaderInitialized, oamReaderBusY, oamReaderBusX, oamReaderDmaSource, oamReaderSourceChangeTicks, spritePosIndex, state, spriteY, spriteHeight, previousOamSpriteHeight, spriteHeightTransitionThisLine, spriteX, dmaBlockedThisLine, i, selectSprites, spriteCandidateSeen
- `eu.rekawek.coffeegb.core.gpu.ColorPixelFifo$ColorPixelFifoMemento`: pixels, palettes, priorities, spriteFifo, delayEntry, delayStamp, delayHead, delaySize, outputTicks, linePixels, clearedPixels, clearedPalettes, clearedPriorities
- `eu.rekawek.coffeegb.core.gpu.Fetcher$FetcherMemento`: pixelLine, state, windowTileX, fetcherY, tileMapAddress, tileId, tileAttributesValue, tileData1, tileData2, insertionGlitchDisabled, data2Pending, data2Delay, data2TileSelectGlitch
- `eu.rekawek.coffeegb.core.ir.FullChanger$FullChangerMemento`: schedule, armed, running, index, remaining
- `eu.rekawek.coffeegb.core.ir.InfraredPort$InfraredPortMemento`: rp, fullChangerMemento
- `eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint$Peer2PeerSerialEndpointMemento`: sb, bitsReceived, bitIndex
- `eu.rekawek.coffeegb.core.serial.GameboyPrinterSerialEndpoint$PrinterMemento`: state, commandId, compression, lengthLeft, commandData, commandLength, checksum, status, byteToSend, image, imageOffset, compressionRunLength, compressionRunIsCompressed, printCountdown, currentReply, sendBits, sb
- `eu.rekawek.coffeegb.core.serial.SerialPort$SerialPortMemento`: sb, sc, serialClocks, serialClockSignal, receivedBits, haltWakeDelay
- `eu.rekawek.coffeegb.core.serial.GpsReceiverSerialEndpoint$GpsReceiverMemento`: ticks, nextStartupBeacon, startupBeacons, outputBytes, outputByte, outputBit, outputTicksRemaining, outputDelayTicks, serialInputHigh, sb, receiveBit, receiveByte, receiveOnes, receiveParityValid, capturingTaip, taipCommand
- `eu.rekawek.coffeegb.core.serial.ByteReceivingSerialEndpoint$ByteReceivingSerialEndpointMemento`: sb, bits
- `eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint$BarcodeBoyMemento`: state, handshakeByte, sendBitIndex, data, dataByte, recvBitIndex, clockDivider
- `eu.rekawek.coffeegb.core.serial.FourPlayerAdapter$AdapterMemento`: sb, transferArmed, pendingBits, connected, consecutiveFf, replies, transmissionBuffer, packetByte, bit, ticksUntilBit, rate, size, phase, transmissionRequested, restartPingRequested
- `eu.rekawek.coffeegb.core.rumble.CodeBreakerRumble$CodeBreakerRumbleMemento`: motorOn
- `eu.rekawek.coffeegb.core.genie.GameGeniePatch`: newData, address, oldData
- `eu.rekawek.coffeegb.core.genie.GameSharkPatch`: mode, bank, address, data
- `eu.rekawek.coffeegb.core.gpu.Gpu$PendingPpuWrite`: address, value, mask, remainingDots
- `eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer$DelayedWindowWrite`: value, remainingDots
