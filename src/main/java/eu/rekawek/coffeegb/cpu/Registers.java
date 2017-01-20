package eu.rekawek.coffeegb.cpu;

import static eu.rekawek.coffeegb.cpu.BitUtils.checkByteArgument;
import static eu.rekawek.coffeegb.cpu.BitUtils.checkWordArgument;
import static eu.rekawek.coffeegb.cpu.BitUtils.getLSB;
import static eu.rekawek.coffeegb.cpu.BitUtils.getMSB;

public class Registers {
    private int a, b, c, d, e, h, l;

    private int sp;

    private int pc;

    private Flags flags = new Flags();

    public int getA() {
        return a;
    }

    public int getB() {
        return b;
    }

    public int getC() {
        return c;
    }

    public int getD() {
        return d;
    }

    public int getE() {
        return e;
    }

    public int getH() {
        return h;
    }

    public int getL() {
        return l;
    }

    public int getAF() {
        return a << 8 | flags.getFlagsByte();
    }

    public int getBC() {
        return b << 8 | c;
    }

    public int getDE() {
        return d << 8 | e;
    }

    public int getHL() {
        return h << 8 | l;
    }

    public int getSP() {
        return sp;
    }

    public int getPC() {
        return pc;
    }

    public Flags getFlags() {
        return flags;
    }

    public void setA(int a) {
        checkByteArgument("a", a);
        this.a = a;
    }

    public void setB(int b) {
        checkByteArgument("b", b);
        this.b = b;
    }

    public void setC(int c) {
        checkByteArgument("c", c);
        this.c = c;
    }

    public void setD(int d) {
        checkByteArgument("d", d);
        this.d = d;
    }

    public void setE(int e) {
        checkByteArgument("e", e);
        this.e = e;
    }

    public void setH(int h) {
        checkByteArgument("h", h);
        this.h = h;
    }

    public void setL(int l) {
        checkByteArgument("l", l);
        this.l = l;
    }

    public void setAF(int af) {
        checkWordArgument("af", af);
        a = getMSB(af);
        flags.setFlagsByte(getLSB(af));
    }

    public void setBC(int bc) {
        checkWordArgument("bc", bc);
        b = getMSB(bc);
        c = getLSB(bc);
    }

    public void setDE(int de) {
        checkWordArgument("de", de);
        d = getMSB(de);
        e = getLSB(de);
    }

    public void setHL(int hl) {
        checkWordArgument("hl", hl);
        h = getMSB(hl);
        l = getLSB(hl);
    }

    public void setSP(int sp) {
        checkWordArgument("sp", sp);
        this.sp = sp;
    }

    public void setPC(int pc) {
        checkWordArgument("pc", pc);
        this.pc = pc;
    }

    public void incrementPC() {
        pc = (pc + 1) & 0xffff;
    }

    public void decrementSP() {
        sp = (sp - 1) & 0xffff;
    }

    public void incrementSP() {
        sp = (sp + 1) & 0xffff;
    }

    @Override
    public String toString() {
        return String.format("AF=%04x, BC=%04x, DE=%04x, HL=%04x, SP=%04x, PC=%04x, %s", getAF(), getBC(), getDE(), getHL(), getSP(), getPC(), getFlags().toString());
    }
}
