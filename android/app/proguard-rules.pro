# The Android runtime now owns BasicController. Its desktop-only historical Java-serialization
# migration is intentionally not exposed by the Android service or UI, but R8 visits its JDK 9
# ObjectInputFilter signature while tracing the controller's public event surface. Android has no
# equivalent class; suppress only this unreachable legacy-import warning, not arbitrary missing
# desktop APIs.
-dontwarn java.io.ObjectInputFilter

# Portable save states reflect over every audited record's canonical constructor and component
# fields. Most roots implement ComponentState, but registered child records (for example cheat
# patches and delayed PPU writes) deliberately do not. StateTypeRegistry resolves all of them by
# their stable binary names, so keep both roots and nested record types intact after R8.
-keepattributes MethodParameters,Signature
-keep class * implements eu.rekawek.coffeegb.core.state.ComponentState { <fields>; <init>(...); }
-keep class eu.rekawek.coffeegb.core.**$*State { <fields>; <init>(...); }

# Session snapshots classify installed link peripherals by their audited binary class names. Keep
# only those names stable; implementations may still be shrunk and optimized normally.
-keepnames class * implements eu.rekawek.coffeegb.core.serial.SerialEndpoint

# The bounded legacy importer resolves its allowlisted compatibility records by their released
# names. These classes are otherwise reflection-only and would be removed from a minified build.
-keep class eu.rekawek.coffeegb.core.**$*Memento { <fields>; <init>(...); }
-keep class eu.rekawek.coffeegb.core.genie.GameGeniePatch { <fields>; <init>(...); }
-keep class eu.rekawek.coffeegb.core.genie.GameSharkPatch { <fields>; <init>(...); }
-keep class eu.rekawek.coffeegb.core.gpu.Gpu$PendingPpuWrite { <fields>; <init>(...); }
-keep class eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer$DelayedWindowWrite {
    <fields>;
    <init>(...);
}

# Audited enum classes are also resolved by stable binary name before their ordinals are encoded.
-keep enum eu.rekawek.coffeegb.core.** { *; }

# Keep the normal-speed monochrome/compatibility settled-HALT lane bodies visible to R8's call
# graph without pinning their class or method names. The tiny selector remains free to inline.
-keepclassmembers,allowobfuscation class eu.rekawek.coffeegb.core.Gameboy {
    private int tryPerformanceSettledCgbCompatHaltSpan(long);
    private int tryPerformanceSettledDmgHaltSpan(long);
    private int tryPerformanceSettledNativeCgbHaltSpan(long);
}

# This native-CGB commit is deliberately kept as the coarse epoch's small call boundary. R8 may
# shrink and rename it, but must not inline its body back into Gameboy's already-large commit.
-keepclassmembers,allowobfuscation,allowshrinking class eu.rekawek.coffeegb.core.gpu.Gpu {
    public void advancePerformanceEpochQuietSpanTrusted(int,boolean,boolean);
}

# Keep the native-CGB scalar STAT prologue out of the shared subsystem scheduler. The one-shot
# owner branch must stay tiny for DMG/ACCURACY ticks while the packed native implementation keeps
# its own optimization boundary.
-keepclassmembers,allowobfuscation,allowshrinking class eu.rekawek.coffeegb.core.Gameboy {
    private void tickNativeCgbPerformanceStatPrologue(int);
}

# Keep the native-CGB scanline dispatch as a narrow renderer optimization boundary. Do not allow
# R8 to inline it into Gpu's scheduler; its body still selects the exact or generic renderer path.
-keepclassmembers,allowobfuscation,allowshrinking class eu.rekawek.coffeegb.core.gpu.PerformanceScanlineRenderer {
    void renderLinePerformanceBoundary(eu.rekawek.coffeegb.core.gpu.Display,int,int);
}
