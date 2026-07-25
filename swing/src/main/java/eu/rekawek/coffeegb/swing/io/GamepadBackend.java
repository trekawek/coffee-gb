package eu.rekawek.coffeegb.swing.io;

import java.util.List;

/** Package-private SDL-free polling seam used by SwingGamepad tests. */
interface GamepadBackend extends AutoCloseable {

    void initialize();

    void update();

    List<DeviceInfo> devices();

    GamepadDevice open(DeviceInfo device);

    @Override
    void close();

    record DeviceInfo(String stableId, String name, int backendIndex) {
    }

    enum Axis { LEFT_X, LEFT_Y, RIGHT_X, RIGHT_Y }

    enum PadButton { UP, DOWN, LEFT, RIGHT, A, B, X, START, BACK }

    interface GamepadDevice extends AutoCloseable {
        String stableId();
        String name();
        boolean attached();
        int axis(Axis axis);
        boolean button(PadButton button);
        void rumble(boolean enabled);
        @Override
        void close();
    }
}
