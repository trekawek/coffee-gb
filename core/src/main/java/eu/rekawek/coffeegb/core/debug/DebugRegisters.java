package eu.rekawek.coffeegb.core.debug;

/** Detached CPU register values captured at one debug safe point. */
public record DebugRegisters(
        int a,
        int f,
        int b,
        int c,
        int d,
        int e,
        int h,
        int l,
        int sp,
        int pc) {

    public DebugRegisters {
        DebugValueChecks.unsignedByte("a", a);
        DebugValueChecks.unsignedByte("f", f);
        DebugValueChecks.unsignedByte("b", b);
        DebugValueChecks.unsignedByte("c", c);
        DebugValueChecks.unsignedByte("d", d);
        DebugValueChecks.unsignedByte("e", e);
        DebugValueChecks.unsignedByte("h", h);
        DebugValueChecks.unsignedByte("l", l);
        DebugValueChecks.unsignedWord("sp", sp);
        DebugValueChecks.unsignedWord("pc", pc);
        if ((f & 0x0f) != 0) {
            throw new IllegalArgumentException("The low nibble of F must be zero: " + f);
        }
    }

    public int af() {
        return a << 8 | f;
    }

    public int bc() {
        return b << 8 | c;
    }

    public int de() {
        return d << 8 | e;
    }

    public int hl() {
        return h << 8 | l;
    }
}
