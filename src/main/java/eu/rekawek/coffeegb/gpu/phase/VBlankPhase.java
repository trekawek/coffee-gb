package eu.rekawek.coffeegb.gpu.phase;

import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;

import java.io.Serializable;

public class VBlankPhase implements GpuPhase, Serializable, Originator<VBlankPhase> {

  private int ticks;

  public VBlankPhase start() {
    ticks = 0;
    return this;
  }

  @Override
  public boolean tick() {
    return ++ticks < 456;
  }

  @Override
  public Memento<VBlankPhase> saveToMemento() {
    return new VBlankPhaseMemento(ticks);
  }

  @Override
  public void restoreFromMemento(Memento<VBlankPhase> memento) {
    if (!(memento instanceof VBlankPhaseMemento mem)) {
      throw new IllegalArgumentException("Invalid memento type");
    }
    this.ticks = mem.ticks;
  }

  private record VBlankPhaseMemento(int ticks) implements Memento<VBlankPhase> {
  }
}