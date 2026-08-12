package eu.rekawek.coffeegb.ui.menu.artwork;

/** An immutable point in a menu source or destination coordinate space. */
public record MenuPoint(double x, double y) {

    public MenuPoint {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Menu coordinates must be finite");
        }
    }
}
