package eu.rekawek.coffeegb.serial;

enum ClockType {
    INTERNAL, EXTERNAL;

    public static ClockType getFromSc(int sc) {
        if ((sc & 1) == 1) {
            return INTERNAL;
        } else {
            return EXTERNAL;
        }
    }
}
