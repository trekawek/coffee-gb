package eu.rekawek.coffeegb.core.state.bess;

import java.util.List;
import java.util.Map;

/** Portable architectural state, without emulator-specific pipeline or timing information. */
public record BessState(String name, byte[] info, Core core, List<MbcWrite> mbcWrites,
                        Map<String, byte[]> extensions, Sgb sgb) {

    public BessState(String name, byte[] info, Core core, List<MbcWrite> mbcWrites,
                     Map<String, byte[]> extensions) {
        this(name, info, core, mbcWrites, extensions, null);
    }

    public BessState {
        mbcWrites = List.copyOf(mbcWrites);
        extensions = Map.copyOf(extensions);
    }

    public record Core(String model, int pc, int af, int bc, int de, int hl, int sp,
                       boolean ime, int ie, int executionState, byte[] io, byte[] ram,
                       byte[] vram, byte[] mbcRam, byte[] oam, byte[] hram,
                       byte[] bgPalettes, byte[] objPalettes) {
    }

    public record MbcWrite(int address, int value) {
    }

    public record Sgb(byte[] borderTiles, byte[] borderTilemap, byte[] borderPalettes,
                      byte[] activePalettes, byte[] ramPalettes, byte[] attributeMap,
                      byte[] attributeFiles, int multiplayerStatus) {
    }
}
