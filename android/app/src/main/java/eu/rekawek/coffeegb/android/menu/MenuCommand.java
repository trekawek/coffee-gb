package eu.rekawek.coffeegb.android.menu;

/** A small, allocation-free command vocabulary for the menu reducer. */
final class MenuCommand {

    enum Type {
        SHOW,
        HIDE,
        MOVE,
        PUSH,
        BACK
    }

    enum Direction {
        UP,
        DOWN,
        LEFT,
        RIGHT
    }

    private final Type type;
    private final Direction direction;
    private final MenuRoute route;

    private MenuCommand(Type type, Direction direction, MenuRoute route) {
        this.type = type;
        this.direction = direction;
        this.route = route;
    }

    static MenuCommand show(MenuRoute route) {
        return new MenuCommand(Type.SHOW, null, route);
    }

    static MenuCommand hide() {
        return new MenuCommand(Type.HIDE, null, null);
    }

    static MenuCommand move(Direction direction) {
        return new MenuCommand(Type.MOVE, direction, null);
    }

    static MenuCommand push(MenuRoute route) {
        return new MenuCommand(Type.PUSH, null, route);
    }

    static MenuCommand back() {
        return new MenuCommand(Type.BACK, null, null);
    }

    Type type() {
        return type;
    }

    Direction direction() {
        return direction;
    }

    MenuRoute route() {
        return route;
    }
}
