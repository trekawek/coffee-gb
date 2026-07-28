package eu.rekawek.coffeegb.swing.packaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exact native-resource allowlists for the package targets supported by issue #338.
 *
 * <p>The paths, sizes, and SHA-256 values below are locked to OpenPnP OpenCV 4.9.0-0,
 * libsdl4j 2.28.4-1.6, and JNA 5.13.0. Dependency updates must deliberately update this manifest
 * and its inventory tests.
 */
public final class NativeBundleManifest {

    static final long MAX_ENTRY_BYTES = 128L * 1024L * 1024L;

    private static final Map<NativeTarget, NativeBundleManifest> LOCKED = lockedManifests();

    private final NativeTarget target;
    private final GamepadNativeSupport gamepadSupport;
    private final List<NativeBundleEntry> entries;

    NativeBundleManifest(
            NativeTarget target,
            GamepadNativeSupport gamepadSupport,
            List<NativeBundleEntry> entries) {
        this.target = Objects.requireNonNull(target, "target");
        this.gamepadSupport = Objects.requireNonNull(gamepadSupport, "gamepadSupport");
        this.entries = List.copyOf(entries);
    }

    public static NativeBundleManifest locked(NativeTarget target) {
        return LOCKED.get(Objects.requireNonNull(target, "target"));
    }

    public NativeTarget target() {
        return target;
    }

    public GamepadNativeSupport gamepadSupport() {
        return gamepadSupport;
    }

    public List<NativeBundleEntry> entries() {
        return entries;
    }

    /** A stable content address covering the target, capability, and every locked entry. */
    public String fingerprint() {
        StringBuilder canonical = new StringBuilder("coffee-gb-native-bundle-v1\n");
        canonical.append(target.id()).append('\n');
        canonical.append(gamepadSupport.name()).append('\n');
        for (NativeBundleEntry entry : entries) {
            canonical.append(entry.component().id()).append('|')
                    .append(entry.resourcePath()).append('|')
                    .append(entry.relativeOutputPath()).append('|')
                    .append(entry.byteSize()).append('|')
                    .append(entry.sha256()).append('\n');
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    String markerContents() {
        StringBuilder marker = new StringBuilder();
        marker.append("schema=1\n");
        marker.append("target=").append(target.id()).append('\n');
        marker.append("fingerprint=").append(fingerprint()).append('\n');
        marker.append("gamepad=").append(gamepadSupport.name()).append('\n');
        for (NativeBundleEntry entry : entries) {
            marker.append("entry=")
                    .append(entry.component().id()).append('|')
                    .append(entry.relativeOutputPath()).append('|')
                    .append(entry.byteSize()).append('|')
                    .append(entry.sha256()).append('\n');
        }
        return marker.toString();
    }

    private static Map<NativeTarget, NativeBundleManifest> lockedManifests() {
        EnumMap<NativeTarget, NativeBundleManifest> manifests = new EnumMap<>(NativeTarget.class);
        manifests.put(
                NativeTarget.LINUX_X86_64,
                new NativeBundleManifest(
                        NativeTarget.LINUX_X86_64,
                        GamepadNativeSupport.BUNDLED,
                        List.of(
                                entry(
                                        NativeComponent.JNA_DISPATCH,
                                        "com/sun/jna/linux-x86-64/libjnidispatch.so",
                                        "lib/libjnidispatch.so",
                                        134447,
                                        "5f5b5f7a43bf5d669b9339876efd999756c887dc85406deb2d8a8ef935045b0f"),
                                entry(
                                        NativeComponent.OPENCV,
                                        "nu/pattern/opencv/linux/x86_64/libopencv_java490.so",
                                        "lib/libopencv_java490.so",
                                        65304272,
                                        "4698bfd0c5d8b032649a9edc32882dcc085c990964350a8ac8e27848fd8e1142"),
                                entry(
                                        NativeComponent.SDL2,
                                        "linux-x86-64/libSDL2.so",
                                        "lib/libSDL2.so",
                                        11532704,
                                        "ada9e0a89c878e6c9d08409149581aabd95ee47740b48ccc916b84c5d2f9774f"))));
        manifests.put(
                NativeTarget.WINDOWS_X86_64,
                new NativeBundleManifest(
                        NativeTarget.WINDOWS_X86_64,
                        GamepadNativeSupport.BUNDLED,
                        List.of(
                                entry(
                                        NativeComponent.JNA_DISPATCH,
                                        "com/sun/jna/win32-x86-64/jnidispatch.dll",
                                        "bin/jnidispatch.dll",
                                        254464,
                                        "13b2cac3f50368ab97fa2e3b0d0d2cb612f68449d5bbd6de187fc85ee4469d03"),
                                entry(
                                        NativeComponent.OPENCV,
                                        "nu/pattern/opencv/windows/x86_64/opencv_java490.dll",
                                        "bin/opencv_java490.dll",
                                        52015616,
                                        "ab87f37830a5fd4d1f2acee8be393e20a7da2027bb298ffef7d5080f8ced06df"),
                                entry(
                                        NativeComponent.SDL2,
                                        "win32-x86-64/SDL2.dll",
                                        "bin/SDL2.dll",
                                        2499072,
                                        "520d0459b91efa32fbccf9027a9ca1fc5aae657e679ce8e90f179f9cf5afd279"))));
        manifests.put(
                NativeTarget.MACOS_X86_64,
                new NativeBundleManifest(
                        NativeTarget.MACOS_X86_64,
                        GamepadNativeSupport.SYSTEM_LIBRARY_REQUIRED,
                        List.of(
                                entry(
                                        NativeComponent.JNA_DISPATCH,
                                        "com/sun/jna/darwin-x86-64/libjnidispatch.jnilib",
                                        "lib/libjnidispatch.jnilib",
                                        107392,
                                        "592b5a09d047bcf302e503174f4e2b2978556b24c7b7069d17f6d30f59e8bd17"),
                                entry(
                                        NativeComponent.OPENCV,
                                        "nu/pattern/opencv/osx/x86_64/libopencv_java490.dylib",
                                        "lib/libopencv_java490.dylib",
                                        55802672,
                                        "2fb0432d892c65e5e11734da13bb506545fbf6152dc9fadaafd87b0b55cac12f"))));
        manifests.put(
                NativeTarget.MACOS_AARCH64,
                new NativeBundleManifest(
                        NativeTarget.MACOS_AARCH64,
                        GamepadNativeSupport.SYSTEM_LIBRARY_REQUIRED,
                        List.of(
                                entry(
                                        NativeComponent.JNA_DISPATCH,
                                        "com/sun/jna/darwin-aarch64/libjnidispatch.jnilib",
                                        "lib/libjnidispatch.jnilib",
                                        159816,
                                        "32223f6ad4d1b3c5651bac44bf83e07207bf995987c6c6d6f839436ed75cf6ea"),
                                entry(
                                        NativeComponent.OPENCV,
                                        "nu/pattern/opencv/osx/ARMv8/libopencv_java490.dylib",
                                        "lib/libopencv_java490.dylib",
                                        22768324,
                                        "95584d05e00814137140295154f267f0deb3a0839c7278aecb389a45781da77b"))));
        return Map.copyOf(manifests);
    }

    private static NativeBundleEntry entry(
            NativeComponent component,
            String resourcePath,
            String relativeOutputPath,
            long byteSize,
            String sha256) {
        return new NativeBundleEntry(component, resourcePath, relativeOutputPath, byteSize, sha256);
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] encoded = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 0xff;
                encoded[i * 2] = Character.forDigit(value >>> 4, 16);
                encoded[i * 2 + 1] = Character.forDigit(value & 0x0f, 16);
            }
            return new String(encoded);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
