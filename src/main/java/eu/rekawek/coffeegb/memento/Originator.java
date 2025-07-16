package eu.rekawek.coffeegb.memento;

public interface Originator<T> {
  Memento<T> saveToMemento();

  void restoreFromMemento(Memento<T> memento);
}
