package eu.rekawek.coffeegb.gpu.phase;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.gpu.GpuRegister;
import eu.rekawek.coffeegb.gpu.GpuRegisterValues;
import eu.rekawek.coffeegb.gpu.Lcdc;

import java.io.Serializable;

public class OamSearch implements GpuPhase, Serializable {

    private enum State {
        READING_Y, READING_X
    }

    public static class SpritePosition implements Serializable {

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
                if (spritePosIndex < sprites.length && between(spriteY, registers.get(GpuRegister.LY) + 16, spriteY + lcdc.getSpriteHeight())) {
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

}
