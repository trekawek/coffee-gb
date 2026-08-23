# The Android runtime now owns BasicController. Its desktop-only historical Java-serialization
# migration is intentionally not exposed by the Android service or UI, but R8 visits its JDK 9
# ObjectInputFilter signature while tracing the controller's public event surface. Android has no
# equivalent class; suppress only this unreachable legacy-import warning, not arbitrary missing
# desktop APIs.
-dontwarn java.io.ObjectInputFilter

# Portable save states reflect over every ComponentState record's canonical constructor and
# component fields. Keep their names and Java parameter metadata stable after R8 so a state file
# created by a release build has the same schema as a debug or desktop build.
-keepattributes MethodParameters,Signature
-keep class * implements eu.rekawek.coffeegb.core.state.ComponentState { <fields>; <init>(...); }

# Keep the DMG settled-HALT side entrance visible to R8's call graph without pinning its class
# or method name; the ordinary PERFORMANCE scheduler remains free to optimize normally.
-keepclassmembers,allowobfuscation class eu.rekawek.coffeegb.core.Gameboy {
    private int tryPerformanceSettledDmgHaltSpan(long);
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
