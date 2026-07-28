package eu.rekawek.coffeegb.swing.packaging;

/** Native components whose exact bytes are locked for desktop package assembly. */
public enum NativeComponent {
    JNA_DISPATCH("jna-dispatch"),
    OPENCV("opencv"),
    SDL2("sdl2");

    private final String id;

    NativeComponent(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
