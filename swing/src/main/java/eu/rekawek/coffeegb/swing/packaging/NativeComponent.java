package eu.rekawek.coffeegb.swing.packaging;

/** Native components whose exact bytes are locked for desktop package assembly. */
public enum NativeComponent {
    JNA_DISPATCH("jna-dispatch", "5.13.0", "Apache-2.0 OR LGPL-2.1-or-later"),
    OPENCV("opencv", "4.9.0", "Apache-2.0"),
    SDL2("sdl2", "2.28.4", "Zlib");

    private final String id;
    private final String version;
    private final String licenseExpression;

    NativeComponent(String id, String version, String licenseExpression) {
        this.id = id;
        this.version = version;
        this.licenseExpression = licenseExpression;
    }

    public String id() {
        return id;
    }

    public String version() {
        return version;
    }

    public String licenseExpression() {
        return licenseExpression;
    }
}
