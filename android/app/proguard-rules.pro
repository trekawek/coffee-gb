# The Android runtime now owns BasicController. Its desktop-only historical Java-serialization
# migration is intentionally not exposed by the Android service or UI, but R8 visits its JDK 9
# ObjectInputFilter signature while tracing the controller's public event surface. Android has no
# equivalent class; suppress only this unreachable legacy-import warning, not arbitrary missing
# desktop APIs.
-dontwarn java.io.ObjectInputFilter
