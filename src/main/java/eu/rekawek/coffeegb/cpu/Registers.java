package eu.rekawek.coffeegb.cpu;

import static eu.rekawek.coffeegb.cpu.BitUtils.abs;
import static eu.rekawek.coffeegb.cpu.BitUtils.checkByteArgument;
import static eu.rekawek.coffeegb.cpu.BitUtils.checkWordArgument;
import static eu.rekawek.coffeegb.cpu.BitUtils.getLSB;
import static eu.rekawek.coffeegb.cpu.BitUtils.getMSB;
import static eu.rekawek.coffeegb.cpu.BitUtils.isNegative;

public class Registers {
    public enum ByteRegisterType {
        A {
            @Override
            public void set(Registers registers, int value) {
                registers.setA(value);
            }

            @Override
            public int get(Registers registers) {
                return registers.getA();
            }
        }, B {
            @Override
            public void set(Registers registers, int value) {
                registers.setB(value);
            }

            @Override
            public int get(Registers registers) {
                return registers.getB();
            }
        }, C {
            @Override
            public void set(Registers registers, int value) {
                registers.setC(value);
            }

            @Override
            public int get(Registers registers) {
                return registers.getC();
            }
        }, D {
            @Override
            public void set(Registers registers, int value) {
                registers.setD(value);
            }

            @Override
            public int get(Registers registers) {
                return registers.getD();
            }
        }, E {
            @Override
            public void set(Registers registers, int value) {
                registers.setE(value);
            }

            @Override
            public int get(Registers registers) {
                return registers.getE();
            }
        }, H {
            @Override
            public void set(Registers registers, int value) {
                registers.setH(value);
            }

            @Override
            public int get(Registers registers) {
                return registers.getH();
            }
        }, L {
            @Override
            public void set(Registers registers, int value) {
                registers.setL(value);
            }

            @Override
            public int get(Registers registers) {
                return registers.getL();
            }
        };

        public abstract void set(Registers registers, int value);

        public abstract int get(Registers registers);
    }

    public enum WordRegisterType {
        AF {
            @Override
            public void set(Registers registers, int value) {
                registers.setAF(value);
            }

            @Override
            public int get(Registers registers) {
                return registers.getAF();
            }
        }, BC {
            @Override
            public void set(Registers registers, int value) {
                registers.setBC(value);
            }

            @Override
            public int get(Registers registers) {
                return registers.getBC();
            }
        }, DE {
            @Override
            public void set(Registers registers, int value) {
                registers.setDE(value);
            }

            @Override
            public int get(Registers registers) {
                return registers.getDE();
            }
        }, HL {
            @Override
            public void set(Registers registers, int value) {
                registers.setHL(value);
            }

            @Override
            public int get(Registers registers) {
                return registers.getHL();
            }
        }, SP {
            @Override
            public void set(Registers registers, int value) {
                registers.setSP(value);
            }

            @Override
            public int get(Registers registers) {
                return registers.getSP();
            }
        }, PC {
            @Override
            public void set(Registers registers, int value) {
                registers.setPC(value);
            }

            @Override
            public int get(Registers registers) {
                return registers.getPC();
            }
        };

        public abstract void set(Registers registers, int value);

        public abstract int get(Registers registers);
    }

    private int a, b, c, d, e, f, h, l;

    private int sp;

    private int pc;

    private Flags flags = new Flags();

    private boolean ime;

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

    public int getF() {
        return f;
    }

    public int getH() {
        return h;
    }

    public int getL() {
        return l;
    }

    public int getAF() {
        return a << 8 | f;
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

    public boolean isIME() {
        return ime;
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

    public void setF(int f) {
        checkByteArgument("f", f);
        this.f = f;
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
        f = getLSB(af);
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

    public void addToPC(int signedByte) {
        checkByteArgument("signedByte", signedByte);
        if (isNegative(signedByte)) {
            pc = (pc - abs(signedByte)) & 0xffff;
        } else {
            pc = (pc + abs(signedByte)) & 0xffff;
        }
    }

    @Override
    public String toString() {
        return String.format("AF=%04x, BC=%04x, DE=%04x, HL=%04x, SP=%04x, PC=%04x, %s", getAF(), getBC(), getDE(), getHL(), getSP(), getPC(), getFlags().toString());
    }
}
