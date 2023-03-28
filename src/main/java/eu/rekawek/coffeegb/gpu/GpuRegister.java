package eu.rekawek.coffeegb.gpu;

import static eu.rekawek.coffeegb.gpu.GpuRegister.RegisterType.*;

public enum GpuRegister {

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

    private final RegisterType type;

    GpuRegister(int address, RegisterType type) {
        this.address = address;
        this.type = type;
    }

    public int getAddress() {
        return address;
    }

    public RegisterType getType() {
        return type;
    }

    public enum RegisterType {
        R(true, false), W(false, true), RW(true, true);

        private final boolean allowsRead;

        private final boolean allowsWrite;

        RegisterType(boolean allowsRead, boolean allowsWrite) {
            this.allowsRead = allowsRead;
            this.allowsWrite = allowsWrite;
        }

        public boolean isAllowsRead() {
            return allowsRead;
        }

        public boolean isAllowsWrite() {
            return allowsWrite;
        }
    }
}
