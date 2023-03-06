package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.memory.MemoryRegisters;

import static eu.rekawek.coffeegb.memory.MemoryRegisters.RegisterType.R;
import static eu.rekawek.coffeegb.memory.MemoryRegisters.RegisterType.RW;
import static eu.rekawek.coffeegb.memory.MemoryRegisters.RegisterType.W;

public enum GpuRegister implements MemoryRegisters.Register {

    STAT(0xff41, RW),
    SCY(0xff42, RW),
    SCX(0xff43, RW),
    LY(0xff44, R),
    LYC(0xff45, RW),
    BGP(0xff47, RW),
    OBP0(0xff48, RW),
    OBP1(0xff49, RW),
    WY(0xff4a, RW),
    WX(0xff4b, RW),
    VBK(0xff4f, W);

    private final int address;

    private final MemoryRegisters.RegisterType type;

    GpuRegister(int address, MemoryRegisters.RegisterType type) {
        this.address = address;
        this.type = type;
    }

    @Override
    public int getAddress() {
        return address;
    }

    @Override
    public MemoryRegisters.RegisterType getType() {
        return type;
    }

}
