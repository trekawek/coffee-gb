package eu.rekawek.coffeegb.core.gpu.phase;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.GpuRegister;
import eu.rekawek.coffeegb.core.gpu.GpuRegisterValues;
import eu.rekawek.coffeegb.core.gpu.Lcdc;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;
import eu.rekawek.coffeegb.core.memory.Dma;

import java.io.Serializable;
import java.util.Arrays;

public class OamSearch implements GpuPhase, Serializable, Originator<OamSearch> {

    private enum State {
        READING_Y,
        READING_X
    }

    public static class SpritePosition implements Serializable, Originator<SpritePosition> {

        private int x;

        private int y;

        private int address;

        private boolean enabled;

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getAddress() {
            return address;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void enable(int x, int y, int address) {
            this.x = x;
            this.y = y;
            this.address = address;
            this.enabled = true;
        }

        public void disable() {
            this.enabled = false;
        }

        @Override
        public Memento<SpritePosition> saveToMemento() {
            return new SpritePositionMemento(x, y, address, enabled);
        }

        @Override
        public void restoreFromMemento(Memento<SpritePosition> memento) {
            if (!(memento instanceof SpritePositionMemento mem)) {
                throw new IllegalArgumentException("Invalid memento type");
            }
            this.x = mem.x;
            this.y = mem.y;
            this.address = mem.address;
            this.enabled = mem.enabled;
        }

        private record SpritePositionMemento(int x, int y, int address, boolean enabled)
                implements Memento<SpritePosition> {
        }
    }

    private final AddressSpace oemRam;

    private final Dma dma;

    private final GpuRegisterValues registers;

    private final SpritePosition[] sprites;

    private final Lcdc lcdc;

    private int spritePosIndex;

    private State state;

    private int spriteY;

    private int spriteHeight;

    private int previousOamSpriteHeight;

    private boolean spriteHeightTransitionThisLine;

    private int spriteX;

    private final int[] oamReaderY;

    private final int[] oamReaderX;

    private boolean oamReaderInitialized;

    private boolean oamReaderDmaSource;

    private int oamReaderSourceChangeTicks;

    private boolean dmaBlockedThisLine;

    private int i;

    private boolean selectSprites;

    private boolean spriteCandidateSeen;

    public OamSearch(AddressSpace oemRam, Dma dma, Lcdc lcdc, GpuRegisterValues registers) {
        this.oemRam = oemRam;
        this.dma = dma;
        this.registers = registers;
        this.lcdc = lcdc;
        this.sprites = new SpritePosition[10];
        this.oamReaderY = new int[40];
        this.oamReaderX = new int[40];
        for (int j = 0; j < sprites.length; j++) {
            this.sprites[j] = new SpritePosition();
        }
    }

    public OamSearch start() {
        return start(true);
    }

    public OamSearch start(boolean selectSprites) {
        this.selectSprites = selectSprites;
        spriteCandidateSeen = false;
        spritePosIndex = 0;
        state = State.READING_Y;
        spriteY = 0;
        previousOamSpriteHeight = getOamSpriteHeight();
        spriteHeightTransitionThisLine = false;
        spriteX = 0;
        dmaBlockedThisLine = false;
        i = 0;
        for (SpritePosition sprite : sprites) {
            sprite.disable();
        }
        return this;
    }

    @Override
    public boolean tick() {
        boolean dmaOwnsOam = dma.ownsOamForPpu();
        dmaBlockedThisLine |= dmaOwnsOam;
        int currentSpriteHeight = getOamSpriteHeight();
        spriteHeightTransitionThisLine |= currentSpriteHeight != previousOamSpriteHeight;
        previousOamSpriteHeight = currentSpriteHeight;
        int spriteAddress = 0xfe00 + 4 * i;
        switch (state) {
            case READING_Y:
                initializeOamReader();
                spriteY = oamReaderY[i];
                spriteX = oamReaderX[i];
                if (registers.isGbc()) {
                    // CGB latches the size source with Y. The X half of the slot below
                    // ORs in the source again, reproducing the old|new crossing state.
                    spriteHeight = currentSpriteHeight;
                }
                state = State.READING_X;
                break;

            case READING_X:
                spriteHeight = registers.isGbc()
                        ? Math.max(spriteHeight, currentSpriteHeight)
                        : currentSpriteHeight;
                boolean candidate = between(spriteY, registers.get(GpuRegister.LY) + 16,
                        spriteY + spriteHeight);
                spriteCandidateSeen |= candidate;
                if (selectSprites && candidate && spritePosIndex < sprites.length) {
                    sprites[spritePosIndex++].enable(spriteX, spriteY, spriteAddress);
                }
                i++;
                state = State.READING_Y;
                break;
        }
        return i < 40;
    }

    /**
     * Advances Gambatte's persistent 80-position OAM reader. Position 0 is sampled
     * at the line boundary; positions 1-79 then precede Coffee GB's mode-2 ticks.
     */
    public void trackDmaSource(int readerPosition) {
        initializeOamReader();
        boolean sourceBeforeTick = dma.ownedOamForPpuBeforeTick();
        boolean sourceAfterTick = dma.ownsOamForPpu();
        boolean sourceChanged = sourceBeforeTick != sourceAfterTick;

        if (readerPosition >= 0 && readerPosition < 80) {
            if ((readerPosition & 1) == 0) {
                int entry = readerPosition / 2;
                int address = 0xfe00 + 4 * entry;
                // DMA writes byte 0 before the PPU runs on an acquisition edge.
                // The reader consumes its cached old-source word first.
                boolean acquisitionCopyEdge = sourceChanged && !sourceBeforeTick;
                if (!acquisitionCopyEdge
                        && (oamReaderSourceChangeTicks > 0 || !oamReaderDmaSource)) {
                    oamReaderY[entry] = oamReaderDmaSource
                            ? 0xff : oemRam.getByte(address);
                    oamReaderX[entry] = oamReaderDmaSource
                            ? 0xff : oemRam.getByte(address + 1);
                }
            }
            if (oamReaderSourceChangeTicks > 0) {
                oamReaderSourceChangeTicks--;
            }
        }

        if (sourceChanged) {
            oamReaderDmaSource = sourceAfterTick;
            oamReaderSourceChangeTicks = 80;
        }
    }

    /** Reconnects the persistent reader after its clock was stopped with the LCD. */
    public void onLcdEnabled() {
        initializeOamReader();
        oamReaderDmaSource = dma.ownsOamForPpu();
        oamReaderSourceChangeTicks = 80;
    }

    private void initializeOamReader() {
        if (oamReaderInitialized) {
            return;
        }
        for (int j = 0; j < 40; j++) {
            oamReaderY[j] = oemRam.getByte(0xfe00 + 4 * j);
            oamReaderX[j] = oemRam.getByte(0xfe01 + 4 * j);
        }
        oamReaderInitialized = true;
    }

    public SpritePosition[] getSprites() {
        return sprites;
    }

    public boolean hadSpriteHeightTransition() {
        return spriteHeightTransitionThisLine;
    }

    public boolean hadSpriteCandidate() {
        return spriteCandidateSeen;
    }

    public boolean wasDmaBlockedThisLine() {
        return dmaBlockedThisLine;
    }

    private int getOamSpriteHeight() {
        // LCDC.2 has its own synchronizer before the OAM reader. Relative to Coffee
        // GB's CPU-before-PPU tick ordering, DMG and CGB double speed sample two ticks
        // back; the normal-speed CGB path includes one additional latch tick.
        int dotsAgo = registers.isGbc() && registers.getSpeedMode() == 1 ? 3 : 2;
        return lcdc.getOamSpriteHeight(dotsAgo);
    }

    private static boolean between(int from, int x, int to) {
        return from <= x && x < to;
    }

    @Override
    public Memento<OamSearch> saveToMemento() {
        Memento<?>[] spriteMementos =
                Arrays.stream(sprites).map(SpritePosition::saveToMemento).toArray(Memento[]::new);
        return new OamSearchMemento(
                spriteMementos, Arrays.copyOf(oamReaderY, oamReaderY.length),
                Arrays.copyOf(oamReaderX, oamReaderX.length),
                oamReaderInitialized, oamReaderDmaSource, oamReaderSourceChangeTicks,
                spritePosIndex, state, spriteY, spriteHeight,
                previousOamSpriteHeight,
                spriteHeightTransitionThisLine, spriteX,
                dmaBlockedThisLine, i,
                selectSprites, spriteCandidateSeen);
    }

    @Override
    public void restoreFromMemento(Memento<OamSearch> memento) {
        if (!(memento instanceof OamSearchMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.sprites.length != mem.sprites.length) {
            throw new IllegalArgumentException("Memento array length doesn't match");
        }
        for (int j = 0; j < sprites.length; j++) {
            sprites[j].restoreFromMemento((Memento<SpritePosition>) mem.sprites[j]);
        }
        System.arraycopy(mem.oamReaderY, 0, oamReaderY, 0, oamReaderY.length);
        System.arraycopy(mem.oamReaderX, 0, oamReaderX, 0, oamReaderX.length);
        this.oamReaderInitialized = mem.oamReaderInitialized;
        this.oamReaderDmaSource = mem.oamReaderDmaSource;
        this.oamReaderSourceChangeTicks = mem.oamReaderSourceChangeTicks;
        this.spritePosIndex = mem.spritePosIndex;
        this.state = mem.state;
        this.spriteY = mem.spriteY;
        this.spriteHeight = mem.spriteHeight;
        this.previousOamSpriteHeight = mem.previousOamSpriteHeight;
        this.spriteHeightTransitionThisLine = mem.spriteHeightTransitionThisLine;
        this.spriteX = mem.spriteX;
        this.dmaBlockedThisLine = mem.dmaBlockedThisLine;
        this.i = mem.i;
        this.selectSprites = mem.selectSprites;
        this.spriteCandidateSeen = mem.spriteCandidateSeen;
    }

    private record OamSearchMemento(
            Memento<?>[] sprites, int[] oamReaderY, int[] oamReaderX,
            boolean oamReaderInitialized, boolean oamReaderDmaSource,
            int oamReaderSourceChangeTicks,
            int spritePosIndex, State state, int spriteY, int spriteHeight,
            int previousOamSpriteHeight,
            boolean spriteHeightTransitionThisLine, int spriteX,
            boolean dmaBlockedThisLine, int i,
            boolean selectSprites, boolean spriteCandidateSeen)
            implements Memento<OamSearch> {
    }
}
