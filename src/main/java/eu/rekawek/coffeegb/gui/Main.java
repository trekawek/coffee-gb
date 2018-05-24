package eu.rekawek.coffeegb.gui;

public class Main {

    public static void main(String[] args) throws Exception {
        System.setProperty("apple.awt.application.name", "Coffee GB");
        new Emulator(args).run();
    }

}
