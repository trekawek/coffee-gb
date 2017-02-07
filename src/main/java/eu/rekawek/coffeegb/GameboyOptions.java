package eu.rekawek.coffeegb;

public class GameboyOptions {

    public static final GameboyOptions DEFAULT = new GameboyOptions();

    private final boolean forceDmg;

    private final boolean useBootstrap;

    public GameboyOptions() {
        forceDmg = false;
        useBootstrap = false;
    }

    public boolean isForceDmg() {
        return forceDmg;
    }

    public boolean isUsingBootstrap() {
        return useBootstrap;
    }
}
