package eu.rekawek.coffeegb.core.memento;

public interface Originator<T> {
    Memento<T> saveToMemento();

    void restoreFromMemento(Memento<T> memento);
}
