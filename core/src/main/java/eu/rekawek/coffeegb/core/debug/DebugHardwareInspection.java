package eu.rekawek.coffeegb.core.debug;

import java.util.Objects;

/**
 * Detached hardware-register and internal-I/O state captured directly from owning components.
 *
 * <p>This payload deliberately does not use the CPU/MMU read path. In particular, peripherals
 * such as the infrared port may react to an ordinary register read, while these values are pure
 * observations taken at the debugger safe point.</p>
 */
public record DebugHardwareInspection(
        Joypad joypad,
        Serial serial,
        Infrared infrared,
        OamDma oamDma,
        VramDma vramDma,
        System system) {

    public DebugHardwareInspection {
        Objects.requireNonNull(joypad, "joypad");
        Objects.requireNonNull(serial, "serial");
        Objects.requireNonNull(infrared, "infrared");
        Objects.requireNonNull(oamDma, "oamDma");
        Objects.requireNonNull(vramDma, "vramDma");
        Objects.requireNonNull(system, "system");
    }

    public record Joypad(
            int joyp,
            int pressedButtonMask,
            int filteredInputLines,
            boolean sgbAvailable,
            int sgbPlayerCount,
            int sgbSelectedPlayer,
            boolean sgbPacketTransferInProgress,
            int sgbPacketByteIndex) {

        public Joypad {
            DebugValueChecks.unsignedByte("joyp", joyp);
            int validButtonMask = (1 << DebugButton.values().length) - 1;
            if ((pressedButtonMask & ~validButtonMask) != 0) {
                throw new IllegalArgumentException("pressedButtonMask contains unknown buttons");
            }
            DebugValueChecks.range("filteredInputLines", filteredInputLines, 0, 0x0f);
            if (sgbAvailable) {
                if (sgbPlayerCount != 1 && sgbPlayerCount != 2 && sgbPlayerCount != 4) {
                    throw new IllegalArgumentException("Invalid SGB player count: " + sgbPlayerCount);
                }
                DebugValueChecks.range("sgbSelectedPlayer", sgbSelectedPlayer, 0, 3);
                DebugValueChecks.range("sgbPacketByteIndex", sgbPacketByteIndex, 0, 16);
            } else if (sgbPlayerCount != 0 || sgbSelectedPlayer != -1
                    || sgbPacketTransferInProgress || sgbPacketByteIndex != -1) {
                throw new IllegalArgumentException("Unavailable SGB state must use sentinel values");
            }
        }
    }

    public record Serial(
            int sb,
            int sc,
            int receivedBits,
            int clockPhase,
            boolean clockSignal,
            int haltWakeDelay) {

        public Serial {
            DebugValueChecks.unsignedByte("sb", sb);
            DebugValueChecks.unsignedByte("sc", sc);
            DebugValueChecks.range("receivedBits", receivedBits, 0, 7);
            DebugValueChecks.unsignedByte("clockPhase", clockPhase);
            DebugValueChecks.range("haltWakeDelay", haltWakeDelay, 0, 4);
        }
    }

    public record Infrared(
            boolean available,
            int rp,
            boolean localOutput,
            boolean receivedLight,
            boolean serialInputHigh) {

        public Infrared {
            if (available) {
                DebugValueChecks.unsignedByte("rp", rp);
            } else if (rp != -1 || localOutput || receivedLight || serialInputHigh) {
                throw new IllegalArgumentException(
                        "Unavailable infrared state must use sentinel values");
            }
        }
    }

    public record OamDma(
            int dma,
            boolean active,
            int sourceAddress,
            int bytesTransferred,
            boolean oamBlocked,
            boolean cpuClockPaused) {

        public OamDma {
            DebugValueChecks.unsignedByte("dma", dma);
            DebugValueChecks.unsignedWord("sourceAddress", sourceAddress);
            DebugValueChecks.range("bytesTransferred", bytesTransferred, 0, 0xa0);
            if (!active && (bytesTransferred != 0 || cpuClockPaused)) {
                throw new IllegalArgumentException(
                        "Inactive OAM DMA cannot retain transfer progress or a paused CPU clock");
            }
        }
    }

    public record VramDma(
            boolean available,
            int hdma1,
            int hdma2,
            int hdma3,
            int hdma4,
            int hdma5,
            boolean active,
            boolean hblankMode,
            int sourceAddress,
            int destinationAddress,
            int currentBlockBytesTransferred) {

        public VramDma {
            if (available) {
                DebugValueChecks.unsignedByte("hdma1", hdma1);
                DebugValueChecks.unsignedByte("hdma2", hdma2);
                DebugValueChecks.unsignedByte("hdma3", hdma3);
                DebugValueChecks.unsignedByte("hdma4", hdma4);
                DebugValueChecks.unsignedByte("hdma5", hdma5);
                DebugValueChecks.unsignedWord("sourceAddress", sourceAddress);
                DebugValueChecks.range("destinationAddress", destinationAddress, 0x8000, 0x9fff);
                DebugValueChecks.range(
                        "currentBlockBytesTransferred", currentBlockBytesTransferred, 0, 16);
                if (!active && currentBlockBytesTransferred != 0) {
                    throw new IllegalArgumentException(
                            "Inactive VRAM DMA cannot retain current-block progress");
                }
            } else if (hdma1 != -1 || hdma2 != -1 || hdma3 != -1 || hdma4 != -1
                    || hdma5 != -1 || active || hblankMode || sourceAddress != -1
                    || destinationAddress != -1 || currentBlockBytesTransferred != -1) {
                throw new IllegalArgumentException(
                        "Unavailable VRAM DMA state must use sentinel values");
            }
        }
    }

    public record System(
            DebugGraphicsHardwareMode hardwareMode,
            int key0,
            int key1,
            int vbk,
            int svbk,
            boolean bootRomMapped,
            int opri,
            int ff72,
            int ff73,
            int ff74,
            int ff75,
            int pcm12,
            int pcm34) {

        public System {
            Objects.requireNonNull(hardwareMode, "hardwareMode");
            optionalByte("key0", key0);
            optionalByte("key1", key1);
            optionalByte("vbk", vbk);
            optionalByte("svbk", svbk);
            optionalByte("opri", opri);
            optionalByte("ff72", ff72);
            optionalByte("ff73", ff73);
            optionalByte("ff74", ff74);
            optionalByte("ff75", ff75);
            optionalByte("pcm12", pcm12);
            optionalByte("pcm34", pcm34);
            boolean cgbHardware = hardwareMode != DebugGraphicsHardwareMode.DMG;
            if (cgbHardware && (key0 < 0 || key1 < 0 || vbk < 0 || svbk < 0 || opri < 0
                    || ff72 < 0 || ff73 < 0 || ff74 < 0 || ff75 < 0
                    || pcm12 < 0 || pcm34 < 0)) {
                throw new IllegalArgumentException(
                        "CGB hardware inspection must expose every system register");
            }
            if (!cgbHardware && (key0 >= 0 || key1 >= 0 || vbk >= 0 || svbk >= 0 || opri >= 0
                    || ff72 >= 0 || ff73 >= 0 || ff74 >= 0 || ff75 >= 0
                    || pcm12 >= 0 || pcm34 >= 0)) {
                throw new IllegalArgumentException(
                        "DMG hardware inspection cannot expose CGB-only system registers");
            }
        }

        private static void optionalByte(String name, int value) {
            if (value != -1) {
                DebugValueChecks.unsignedByte(name, value);
            }
        }
    }
}
