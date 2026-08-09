package eu.rekawek.coffeegb.core.gpu;

final class GpuTimingSnapshot {

    int line;
    int ticksInLine;
    int visibleLy;
    int earlyLineEdgeTick;
    int mode0InterruptTick;
    int cpuMachineCycleDots;

    boolean dmgCompat;
    boolean lcdEnabled;
    boolean firstLine;
    boolean statModeLatchRephasedBySpeedSwitch;
    boolean mode0HaltWakeTick;
    boolean mode0IntWindow;
    boolean mode1IntWindow;
    boolean mode2IntWindow;
    boolean doubleSpeed;
    boolean nativeDoubleSpeed;
}
