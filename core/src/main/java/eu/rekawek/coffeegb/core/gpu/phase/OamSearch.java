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

    private boolean spriteDmaBlocked;

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
        spriteDmaBlocked = false;
        dmaBlockedThisLine = false;
        i = 0;
        for (SpritePosition sprite : sprites) {
            sprite.disable();
        }
        return this;
    }

    @Override
    public boolean tick() {
        int currentSpriteHeight = getOamSpriteHeight();
        spriteHeightTransitionThisLine |= currentSpriteHeight != previousOamSpriteHeight;
        previousOamSpriteHeight = currentSpriteHeight;
        int spriteAddress = 0xfe00 + 4 * i;
        switch (state) {
            case READING_Y:
                spriteY = oemRam.getByte(spriteAddress);
                if (registers.isGbc()) {
                    // CGB latches the size source with Y. The X half of the slot below
                    // ORs in the source again, reproducing the old|new crossing state.
                    spriteHeight = currentSpriteHeight;
                }
                spriteDmaBlocked = dma.isTransferInProgress();
                dmaBlockedThisLine |= spriteDmaBlocked;
                state = State.READING_X;
                break;

            case READING_X:
                spriteX = oemRam.getByte(spriteAddress + 1);
                spriteDmaBlocked |= dma.isTransferInProgress();
                dmaBlockedThisLine |= spriteDmaBlocked;
                spriteHeight = registers.isGbc()
                        ? Math.max(spriteHeight, currentSpriteHeight)
                        : currentSpriteHeight;
                boolean candidate = !spriteDmaBlocked
                        && between(spriteY, registers.get(GpuRegister.LY) + 16,
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
                spriteMementos, spritePosIndex, state, spriteY, spriteHeight,
                previousOamSpriteHeight,
                spriteHeightTransitionThisLine, spriteX, spriteDmaBlocked,
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
        this.spritePosIndex = mem.spritePosIndex;
        this.state = mem.state;
        this.spriteY = mem.spriteY;
        this.spriteHeight = mem.spriteHeight;
        this.previousOamSpriteHeight = mem.previousOamSpriteHeight;
        this.spriteHeightTransitionThisLine = mem.spriteHeightTransitionThisLine;
        this.spriteX = mem.spriteX;
        this.spriteDmaBlocked = mem.spriteDmaBlocked;
        this.dmaBlockedThisLine = mem.dmaBlockedThisLine;
        this.i = mem.i;
        this.selectSprites = mem.selectSprites;
        this.spriteCandidateSeen = mem.spriteCandidateSeen;
    }

    private record OamSearchMemento(
            Memento<?>[] sprites, int spritePosIndex, State state, int spriteY, int spriteHeight,
            int previousOamSpriteHeight,
            boolean spriteHeightTransitionThisLine, int spriteX, boolean spriteDmaBlocked,
            boolean dmaBlockedThisLine, int i,
            boolean selectSprites, boolean spriteCandidateSeen)
            implements Memento<OamSearch> {
    }
}
