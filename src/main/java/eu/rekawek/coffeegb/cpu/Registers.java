package eu.rekawek.coffeegb.cpu;

import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;

import java.io.Serializable;

import static eu.rekawek.coffeegb.cpu.BitUtils.*;

public class Registers implements Serializable, Originator<Registers> {
  private int a, b, c, d, e, h, l;

  private int sp;

  private int pc;

  private final Flags flags = new Flags();

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

  @Override
  public String toString() {
    return String.format(
        "AF=%04x, BC=%04x, DE=%04x, HL=%04x, SP=%04x, PC=%04x, %s",
        getAF(), getBC(), getDE(), getHL(), getSP(), getPC(), getFlags().toString());
  }

  @Override
  public Memento<Registers> saveToMemento() {
    return new RegistersMemento(a, b, c, d, e, h, l, sp, pc, flags.getFlagsByte());
  }

  @Override
  public void restoreFromMemento(Memento<Registers> memento) {
    if (!(memento instanceof RegistersMemento mem)) {
      throw new IllegalArgumentException("Invalid memento type");
    }
    this.a = mem.a;
    this.b = mem.b;
    this.c = mem.c;
    this.d = mem.d;
    this.e = mem.e;
    this.h = mem.h;
    this.l = mem.l;
    this.sp = mem.sp;
    this.pc = mem.pc;
    this.flags.setFlagsByte(mem.flags);
  }

  private record RegistersMemento(int a, int b, int c, int d, int e, int h, int l, int sp, int pc, int flags) implements Memento<Registers> {}
}
