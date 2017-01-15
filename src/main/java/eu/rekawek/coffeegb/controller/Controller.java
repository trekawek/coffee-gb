package eu.rekawek.coffeegb.controller;

public interface Controller {

    void setButtonListener(ButtonListener listener);

    Controller NULL_CONTROLLER = listener -> {};
}
