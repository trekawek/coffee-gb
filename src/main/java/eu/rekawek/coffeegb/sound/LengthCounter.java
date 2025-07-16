package eu.rekawek.coffeegb.sound;

import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;

import java.io.Serializable;

import static eu.rekawek.coffeegb.Gameboy.TICKS_PER_SEC;

public class LengthCounter implements Serializable, Originator<LengthCounter> {

  private final int DIVIDER = TICKS_PER_SEC / 256;

  private final int fullLength;

  private int length;

  private long i;

  private boolean enabled;

  public LengthCounter(int fullLength) {
    this.fullLength = fullLength;
  }

  public void start() {
    i = 8192;
  }

  public void tick() {
    if (++i == DIVIDER) {
      i = 0;
      if (enabled && length > 0) {
        length--;
      }
    }
  }

  public void setLength(int length) {
    if (length == 0) {
      this.length = fullLength;
    } else {
      this.length = length;
    }
  }

  public void setNr4(int value) {
    boolean enable = (value & (1 << 6)) != 0;
    boolean trigger = (value & (1 << 7)) != 0;

    if (enabled) {
      if (length == 0 && trigger) {
        if (enable && i < DIVIDER / 2) {
          setLength(fullLength - 1);
        } else {
          setLength(fullLength);
        }
      }
    } else if (enable) {
      if (length > 0 && i < DIVIDER / 2) {
        length--;
      }
      if (length == 0 && trigger && i < DIVIDER / 2) {
        setLength(fullLength - 1);
      }
    } else {
      if (length == 0 && trigger) {
        setLength(fullLength);
      }
    }
    this.enabled = enable;
  }

  public int getValue() {
    return length;
  }

  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public String toString() {
    return String.format(
        "LengthCounter[l=%d,f=%d,c=%d,%s]",
        length, fullLength, i, enabled ? "enabled" : "disabled");
  }

  void reset() {
    this.enabled = true;
    this.i = 0;
    this.length = 0;
  }

  @Override
  public Memento<LengthCounter> saveToMemento() {
    return new LengthCounterMemento(length, i, enabled);
  }

  @Override
  public void restoreFromMemento(Memento<LengthCounter> memento) {
    if (!(memento instanceof LengthCounterMemento mem)) {
      throw new IllegalArgumentException("Invalid memento type");
    }
    this.length = mem.length;
    this.i = mem.i;
    this.enabled = mem.enabled;
  }

  private record LengthCounterMemento(int length, long i, boolean enabled) implements Memento<LengthCounter> {
  }
}
