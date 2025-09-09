package eu.rekawek.coffeegb.core.gpu.phase;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.GpuRegister;
import eu.rekawek.coffeegb.core.gpu.GpuRegisterValues;
import eu.rekawek.coffeegb.core.gpu.Lcdc;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

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

    private final GpuRegisterValues registers;

    private final SpritePosition[] sprites;

    private final Lcdc lcdc;

    private int spritePosIndex;

    private State state;

    private int spriteY;

    private int spriteX;

    private int i;

    public OamSearch(AddressSpace oemRam, Lcdc lcdc, GpuRegisterValues registers) {
        this.oemRam = oemRam;
        this.registers = registers;
        this.lcdc = lcdc;
        this.sprites = new SpritePosition[10];
        for (int j = 0; j < sprites.length; j++) {
            this.sprites[j] = new SpritePosition();
        }
    }

    public OamSearch start() {
        spritePosIndex = 0;
        state = State.READING_Y;
        spriteY = 0;
        spriteX = 0;
        i = 0;
        for (SpritePosition sprite : sprites) {
            sprite.disable();
        }
        return this;
    }

    @Override
    public boolean tick() {
        int spriteAddress = 0xfe00 + 4 * i;
        switch (state) {
            case READING_Y:
                spriteY = oemRam.getByte(spriteAddress);
                state = State.READING_X;
                break;

            case READING_X:
                spriteX = oemRam.getByte(spriteAddress + 1);
                if (spritePosIndex < sprites.length
                        && between(
                        spriteY, registers.get(GpuRegister.LY) + 16, spriteY + lcdc.getSpriteHeight())) {
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

    private static boolean between(int from, int x, int to) {
        return from <= x && x < to;
    }

    @Override
    public Memento<OamSearch> saveToMemento() {
        Memento<?>[] spriteMementos =
                Arrays.stream(sprites).map(SpritePosition::saveToMemento).toArray(Memento[]::new);
        return new OamSearchMemento(spriteMementos, spritePosIndex, state, spriteY, spriteX, i);
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
        this.spriteX = mem.spriteX;
        this.i = mem.i;
    }

    private record OamSearchMemento(
            Memento<?>[] sprites, int spritePosIndex, State state, int spriteY, int spriteX, int i)
            implements Memento<OamSearch> {
    }
}