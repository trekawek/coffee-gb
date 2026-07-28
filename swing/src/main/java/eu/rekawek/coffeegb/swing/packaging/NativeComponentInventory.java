package eu.rekawek.coffeegb.swing.packaging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Locked target-specific inventory for packaged native libraries and code statically embedded in
 * OpenPnP OpenCV 4.9.0-0.
 *
 * <p>The OpenCV entries are derived from the build information embedded in each locked binary,
 * not from the dependency POM alone. Source and legal references are fixed to OpenPnP tag
 * {@code v4.9.0-0} (commit {@code 8545053}) and OpenCV tag {@code 4.9.0} (commit
 * {@code dad8af6}). A dependency update must deliberately update this inventory, the locked native
 * hashes, legal resources, documentation, and package integration tests together.
 */
public final class NativeComponentInventory {

    public static final String STAGED_NATIVE_SBOM = "coffee-gb-native-sbom.cdx.json";
    private static final long MAX_NATIVE_SBOM_BYTES = 4L * 1024L * 1024L;

    private static final String OPENPNP_SOURCE =
            "https://github.com/openpnp/opencv/tree/854505364c4b1394380ef639348840824effc6ec";
    private static final String OPENCV_SOURCE =
            "https://github.com/opencv/opencv/tree/dad8af6b17f8e60d7b95a1203a1b4d22f56574cf";

    private static final List<String> BASE_LEGAL_PATHS = List.of(
            "LICENSE.txt",
            "THIRD-PARTY-NOTICES.txt",
            "licenses/Apache-2.0.txt",
            "licenses/JLine-BSD-3-Clause.txt",
            "licenses/JNA-DUAL-LICENSE.txt",
            "licenses/LGPL-2.1.txt",
            "licenses/SDL2-zlib.txt");

    private static final Map<NativeTarget, List<NativeSbomComponent>> LOCKED =
            lockedInventories();

    private NativeComponentInventory() {
    }

    public static List<NativeSbomComponent> components(NativeTarget target) {
        return LOCKED.get(Objects.requireNonNull(target, "target"));
    }

    /** Exact legal files copied into a package for this target. */
    public static Set<String> requiredLegalPaths(NativeTarget target) {
        LinkedHashSet<String> paths = new LinkedHashSet<>(BASE_LEGAL_PATHS);
        components(target).stream()
                .flatMap(component -> component.legalFiles().stream())
                .forEach(paths::add);
        return Set.copyOf(paths);
    }

    /** Union used by repository inventory tests; a staged package receives only its target set. */
    public static Set<String> allLegalPaths() {
        LinkedHashSet<String> paths = new LinkedHashSet<>(BASE_LEGAL_PATHS);
        for (NativeTarget target : NativeTarget.values()) {
            paths.addAll(requiredLegalPaths(target));
        }
        return Set.copyOf(paths);
    }

    public static void writeNativeSbom(
            NativeTarget target, String applicationVersion, Path output) throws IOException {
        Files.writeString(
                output,
                renderNativeSbom(target, applicationVersion),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    public static void verifyNativeSbom(
            Path sbom, NativeTarget target, String applicationVersion) throws IOException {
        if (Files.isSymbolicLink(sbom)
                || !Files.isRegularFile(sbom, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Native CycloneDX SBOM is not a regular non-symlink file: " + sbom);
        }
        long size = Files.size(sbom);
        if (size <= 0 || size > MAX_NATIVE_SBOM_BYTES) {
            throw new IOException("Native CycloneDX SBOM has invalid size " + size);
        }
        String expected = renderNativeSbom(target, applicationVersion);
        String actual = Files.readString(sbom, StandardCharsets.UTF_8);
        if (!expected.equals(actual)) {
            throw new IOException(
                    "Native CycloneDX SBOM does not match the locked "
                            + target.id()
                            + " component inventory");
        }
    }

    static String renderNativeSbom(NativeTarget target, String applicationVersion) {
        Objects.requireNonNull(target, "target");
        NativePackageMetadata.installerVersion(applicationVersion);
        String rootRef =
                "urn:coffee-gb:native:"
                        + target.id()
                        + ":"
                        + applicationVersion;
        List<NativeSbomComponent> components = components(target).stream()
                .sorted(Comparator.comparing(NativeSbomComponent::id))
                .toList();

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"$schema\": \"http://cyclonedx.org/schema/bom-1.6.schema.json\",\n");
        json.append("  \"bomFormat\": \"CycloneDX\",\n");
        json.append("  \"specVersion\": \"1.6\",\n");
        json.append("  \"version\": 1,\n");
        json.append("  \"metadata\": {\n");
        json.append("    \"component\": {\n");
        json.append("      \"type\": \"application\",\n");
        field(json, 6, "bom-ref", rootRef, true);
        field(json, 6, "group", "eu.rekawek.coffeegb", true);
        field(json, 6, "name", "coffee-gb-native", true);
        field(json, 6, "version", applicationVersion, true);
        json.append("      \"properties\": [\n");
        property(json, 8, "coffee-gb:native-target", target.id(), false);
        json.append("      ]\n");
        json.append("    }\n");
        json.append("  },\n");
        json.append("  \"components\": [\n");
        for (int i = 0; i < components.size(); i++) {
            NativeSbomComponent component = components.get(i);
            json.append("    {\n");
            json.append("      \"type\": \"library\",\n");
            field(json, 6, "bom-ref", bomRef(target, component), true);
            if (!component.group().isBlank()) {
                field(json, 6, "group", component.group(), true);
            }
            field(json, 6, "name", component.name(), true);
            field(json, 6, "version", component.version(), true);
            if (!component.purl().isBlank()) {
                field(json, 6, "purl", component.purl(), true);
            }
            json.append("      \"scope\": \"required\",\n");
            if (component.sha256() != null) {
                json.append("      \"hashes\": [\n");
                json.append("        {\"alg\": \"SHA-256\", \"content\": \"")
                        .append(component.sha256())
                        .append("\"}\n");
                json.append("      ],\n");
            }
            json.append("      \"licenses\": [\n");
            if (component.licenseExpression() != null) {
                json.append("        {\"expression\": \"")
                        .append(escape(component.licenseExpression()))
                        .append("\"}\n");
            } else {
                json.append("        {\"license\": {\"name\": \"")
                        .append(escape(component.licenseName()))
                        .append("\"}}\n");
            }
            json.append("      ],\n");
            json.append("      \"externalReferences\": [\n");
            json.append("        {\"type\": \"vcs\", \"url\": \"")
                    .append(escape(component.sourceRef()))
                    .append("\"}\n");
            json.append("      ],\n");
            json.append("      \"properties\": [\n");
            List<Property> properties = new ArrayList<>();
            properties.add(new Property("coffee-gb:native-target", target.id()));
            properties.add(new Property(
                    "coffee-gb:distribution",
                    component.embeddedIn() == null ? "bundled-native" : "embedded-static"));
            properties.add(new Property(
                    "coffee-gb:legal-files", String.join(",", component.legalFiles())));
            properties.add(new Property("coffee-gb:evidence", component.evidence()));
            if (component.embeddedIn() != null) {
                properties.add(new Property("coffee-gb:embedded-in", component.embeddedIn()));
            }
            for (int property = 0; property < properties.size(); property++) {
                Property value = properties.get(property);
                property(
                        json,
                        8,
                        value.name(),
                        value.value(),
                        property + 1 < properties.size());
            }
            json.append("      ]\n");
            json.append("    }");
            json.append(i + 1 < components.size() ? ",\n" : "\n");
        }
        json.append("  ],\n");
        json.append("  \"dependencies\": [\n");
        json.append("    {\n");
        field(json, 6, "ref", rootRef, true);
        json.append("      \"dependsOn\": [\n");
        for (int i = 0; i < components.size(); i++) {
            json.append("        \"")
                    .append(escape(bomRef(target, components.get(i))))
                    .append("\"");
            json.append(i + 1 < components.size() ? ",\n" : "\n");
        }
        json.append("      ]\n");
        json.append("    }\n");
        json.append("  ]\n");
        json.append("}\n");
        return json.toString();
    }

    private static Map<NativeTarget, List<NativeSbomComponent>> lockedInventories() {
        EnumMap<NativeTarget, List<NativeSbomComponent>> inventories =
                new EnumMap<>(NativeTarget.class);
        for (NativeTarget target : NativeTarget.values()) {
            List<NativeSbomComponent> components = new ArrayList<>();
            for (NativeBundleEntry entry : NativeBundleManifest.locked(target).entries()) {
                components.add(nativeLibrary(entry));
            }
            components.addAll(commonOpenCvComponents());
            switch (target) {
                case LINUX_X86_64 -> {
                    components.add(ipp(
                            "intel-ipp",
                            "Intel Integrated Performance Primitives",
                            "2021.10.0",
                            "licenses/Intel-IPP-2021.10.0-third-party-programs.txt",
                            "Intel IPP: 2021.10.0 [2021.10.0]"));
                    components.add(ipp(
                            "intel-ipp-iw",
                            "Intel IPP Integration Wrappers",
                            "2021.10.0",
                            "licenses/Intel-IPP-2021.10.0-third-party-programs.txt",
                            "Intel IPP IW: sources (2021.10.0)"));
                    components.add(openClHeaders());
                }
                case WINDOWS_X86_64 -> {
                    components.add(ipp(
                            "intel-ipp",
                            "Intel Integrated Performance Primitives",
                            "2021.11.0",
                            "licenses/Intel-IPP-Windows-4.9.0-third-party-programs.txt",
                            "Intel IPP: 2021.11.0 [2021.11.0]"));
                    components.add(ipp(
                            "intel-ipp-iw",
                            "Intel IPP Integration Wrappers",
                            "2021.11.0",
                            "licenses/Intel-IPP-Windows-4.9.0-third-party-programs.txt",
                            "Intel IPP IW: sources (2021.11.0)"));
                    components.add(openClHeaders());
                    components.add(component(
                            "ade",
                            "opencv",
                            "ADE",
                            "0.1.2d",
                            "pkg:github/opencv/ade@v0.1.2d",
                            "Apache-2.0",
                            null,
                            List.of("licenses/Apache-2.0.txt"),
                            "https://github.com/opencv/ade/tree/v0.1.2d",
                            "opencv-native",
                            "3rdparty dependencies: ade"));
                    components.add(component(
                            "vasot",
                            "intel",
                            "Visual Analytics System Object Tracking",
                            "opencv-4.9.0",
                            "",
                            "MIT",
                            null,
                            List.of("licenses/OpenCV-vaSOT-MIT.txt"),
                            OPENCV_SOURCE
                                    + "/modules/gapi/src/3rdparty/vasot",
                            "opencv-native",
                            "OpenCV gapi built; installed license: vasot-LICENSE.txt"));
                }
                case MACOS_X86_64 -> {
                    components.add(ipp(
                            "intel-ipp",
                            "Intel Integrated Performance Primitives",
                            "2021.9.1",
                            "licenses/Intel-IPP-2021.9.1-third-party-programs.txt",
                            "Intel IPP: 2021.9.1 [2021.9.1]"));
                    components.add(ipp(
                            "intel-ipp-iw",
                            "Intel IPP Integration Wrappers",
                            "2021.9.1",
                            "licenses/Intel-IPP-2021.9.1-third-party-programs.txt",
                            "Intel IPP IW: sources (2021.9.1)"));
                }
                case MACOS_AARCH64 -> {
                    components.add(component(
                            "nvidia-carotene",
                            "nvidia",
                            "Carotene",
                            "0.0.1",
                            "",
                            "BSD-3-Clause",
                            null,
                            List.of(
                                    "licenses/OpenCV-NVIDIA-Carotene-BSD-3-Clause.txt",
                                    "licenses/OpenCV-Carotene-FAST-BSD-3-Clause.txt"),
                            OPENCV_SOURCE + "/3rdparty/carotene",
                            "opencv-native",
                            "Custom HAL: carotene (ver 0.0.1, Auto detected)"));
                    components.add(component(
                            "nvidia-tegra-hal",
                            "nvidia",
                            "OpenCV Tegra HAL",
                            "opencv-4.9.0",
                            "",
                            "BSD-3-Clause",
                            null,
                            List.of("licenses/OpenCV-NVIDIA-Tegra-HAL-BSD-3-Clause.txt"),
                            OPENCV_SOURCE + "/3rdparty/carotene/hal",
                            "opencv-native",
                            "3rdparty dependencies: tegra_hal"));
                }
            }
            inventories.put(target, List.copyOf(components));
        }
        return Map.copyOf(inventories);
    }

    private static NativeSbomComponent nativeLibrary(NativeBundleEntry entry) {
        return switch (entry.component()) {
            case JNA_DISPATCH -> component(
                    "jna-dispatch",
                    "net.java.dev.jna",
                    "JNA native dispatch",
                    "5.13.0",
                    "pkg:maven/net.java.dev.jna/jna@5.13.0",
                    "Apache-2.0 OR LGPL-2.1-or-later",
                    null,
                    List.of(
                            "licenses/JNA-DUAL-LICENSE.txt",
                            "licenses/Apache-2.0.txt",
                            "licenses/LGPL-2.1.txt"),
                    "https://github.com/java-native-access/jna/tree/5.13.0",
                    null,
                    "locked native source entry " + entry.resourcePath(),
                    entry.sha256());
            case OPENCV -> component(
                    "opencv-native",
                    "org.openpnp",
                    "OpenPnP OpenCV native library",
                    "4.9.0-0",
                    "pkg:maven/org.openpnp/opencv@4.9.0-0",
                    "BSD-3-Clause AND Apache-2.0",
                    null,
                    List.of(
                            "licenses/OpenPnP-OpenCV-BSD-3-Clause.txt",
                            "licenses/Apache-2.0.txt",
                            "licenses/OpenCV-COPYRIGHT.txt"),
                    OPENPNP_SOURCE,
                    null,
                    "locked native source entry " + entry.resourcePath(),
                    entry.sha256());
            case SDL2 -> component(
                    "sdl2",
                    "libsdl",
                    "SDL2",
                    "2.28.4",
                    "pkg:github/libsdl-org/SDL@release-2.28.4",
                    "Zlib",
                    null,
                    List.of("licenses/SDL2-zlib.txt"),
                    "https://github.com/libsdl-org/SDL/tree/release-2.28.4",
                    null,
                    "locked libsdl4j 2.28.4-1.6 native source entry "
                            + entry.resourcePath(),
                    entry.sha256());
        };
    }

    private static List<NativeSbomComponent> commonOpenCvComponents() {
        return List.of(
                component(
                        "opencv",
                        "opencv",
                        "OpenCV",
                        "4.9.0",
                        "pkg:github/opencv/opencv@4.9.0",
                        "Apache-2.0",
                        null,
                        List.of(
                                "licenses/Apache-2.0.txt",
                                "licenses/OpenCV-COPYRIGHT.txt"),
                        OPENCV_SOURCE,
                        "opencv-native",
                        "General configuration for OpenCV 4.9.0"),
                component(
                        "ittnotify",
                        "intel",
                        "Intel ITT Notify",
                        "20151119",
                        "",
                        "BSD-3-Clause OR GPL-2.0-only",
                        null,
                        List.of(
                                "licenses/OpenCV-ittnotify-LICENSE.BSD.txt",
                                "licenses/OpenCV-ittnotify-LICENSE.GPL.txt"),
                        OPENCV_SOURCE + "/3rdparty/ittnotify",
                        "opencv-native",
                        "3rdparty dependencies: ittnotify; API_VERSION_BUILD 20151119"),
                component(
                        "protobuf",
                        "google",
                        "Protocol Buffers",
                        "3.19.1",
                        "pkg:github/protocolbuffers/protobuf@v3.19.1",
                        "BSD-3-Clause",
                        null,
                        List.of("licenses/OpenCV-protobuf-LICENSE.txt"),
                        OPENCV_SOURCE + "/3rdparty/protobuf",
                        "opencv-native",
                        "Protobuf: build (3.19.1)"),
                component(
                        "libjpeg-turbo",
                        "libjpeg-turbo",
                        "libjpeg-turbo",
                        "2.1.3-62",
                        "pkg:github/libjpeg-turbo/libjpeg-turbo@2.1.3",
                        "BSD-3-Clause AND Zlib AND LicenseRef-IJG",
                        null,
                        List.of(
                                "licenses/OpenCV-libjpeg-turbo-LICENSE.txt",
                                "licenses/OpenCV-libjpeg-turbo-README.ijg.txt"),
                        OPENCV_SOURCE + "/3rdparty/libjpeg-turbo",
                        "opencv-native",
                        "JPEG: build-libjpeg-turbo (ver 2.1.3-62)"),
                component(
                        "libwebp",
                        "webmproject",
                        "libwebp",
                        "1.3.1",
                        "pkg:github/webmproject/libwebp@v1.3.1",
                        "BSD-3-Clause",
                        null,
                        List.of("licenses/OpenCV-libwebp-COPYING.txt"),
                        OPENCV_SOURCE + "/3rdparty/libwebp",
                        "opencv-native",
                        "WEBP build; embedded WebPGetEncoderVersion 0x010301"),
                component(
                        "libpng",
                        "libpng",
                        "libpng",
                        "1.6.37",
                        "pkg:github/pnggroup/libpng@v1.6.37",
                        "Libpng",
                        null,
                        List.of("licenses/OpenCV-libpng-LICENSE.txt"),
                        OPENCV_SOURCE + "/3rdparty/libpng",
                        "opencv-native",
                        "PNG: build (ver 1.6.37)"),
                component(
                        "libtiff",
                        "libtiff",
                        "LibTIFF",
                        "4.2.0",
                        "pkg:github/libsdl-org/libtiff@v4.2.0",
                        "libtiff",
                        null,
                        List.of("licenses/OpenCV-libtiff-COPYRIGHT.txt"),
                        OPENCV_SOURCE + "/3rdparty/libtiff",
                        "opencv-native",
                        "TIFF: build (ver 42 - 4.2.0)"),
                component(
                        "openjpeg",
                        "uclouvain",
                        "OpenJPEG",
                        "2.5.0",
                        "pkg:github/uclouvain/openjpeg@v2.5.0",
                        "BSD-2-Clause",
                        null,
                        List.of("licenses/OpenCV-OpenJPEG-LICENSE.txt"),
                        OPENCV_SOURCE + "/3rdparty/openjpeg",
                        "opencv-native",
                        "JPEG 2000: build (ver 2.5.0)"),
                component(
                        "openexr",
                        "openexr",
                        "OpenEXR",
                        "2.3.0",
                        "pkg:github/AcademySoftwareFoundation/openexr@v2.3.0",
                        "BSD-3-Clause",
                        null,
                        List.of(
                                "licenses/OpenCV-OpenEXR-LICENSE.txt",
                                "licenses/OpenCV-OpenEXR-AUTHORS.ilmbase.txt",
                                "licenses/OpenCV-OpenEXR-AUTHORS.openexr.txt"),
                        OPENCV_SOURCE + "/3rdparty/openexr",
                        "opencv-native",
                        "OpenEXR: build (ver 2.3.0)"),
                component(
                        "zlib",
                        "zlib",
                        "zlib",
                        "1.3",
                        "pkg:github/madler/zlib@v1.3",
                        "Zlib",
                        null,
                        List.of("licenses/OpenCV-zlib-LICENSE.txt"),
                        OPENCV_SOURCE + "/3rdparty/zlib",
                        "opencv-native",
                        "ZLib: build (ver 1.3)"),
                component(
                        "flatbuffers",
                        "google",
                        "FlatBuffers",
                        "23.5.9",
                        "pkg:github/google/flatbuffers@v23.5.9",
                        "Apache-2.0",
                        null,
                        List.of("licenses/Apache-2.0.txt"),
                        OPENCV_SOURCE + "/3rdparty/flatbuffers",
                        "opencv-native",
                        "Flatbuffers: builtin/3rdparty (23.5.9)"),
                component(
                        "softfloat",
                        "berkeley",
                        "Berkeley SoftFloat",
                        "3c",
                        "",
                        "BSD-3-Clause",
                        null,
                        List.of("licenses/OpenCV-SoftFloat-COPYING.txt"),
                        OPENCV_SOURCE + "/modules/core/3rdparty/SoftFloat",
                        "opencv-native",
                        "OpenCV core installed third-party license: SoftFloat-COPYING.txt"),
                component(
                        "mscr-chi-table",
                        "opencv",
                        "MSCR chi table",
                        "opencv-4.9.0",
                        "",
                        "BSD-3-Clause",
                        null,
                        List.of("licenses/OpenCV-MSCR-chi-table-LICENSE.txt"),
                        OPENCV_SOURCE + "/modules/features2d/3rdparty/mscr",
                        "opencv-native",
                        "OpenCV features2d installed third-party license: "
                                + "mscr-chi_table_LICENSE.txt"));
    }

    private static NativeSbomComponent ipp(
            String id,
            String name,
            String version,
            String thirdPartyPrograms,
            String evidence) {
        return component(
                id,
                "intel",
                name,
                version,
                "pkg:generic/" + id + "@" + version,
                null,
                "Intel Simplified Software License (October 2022)",
                List.of(
                        "licenses/Intel-IPP-EULA-October-2022.txt",
                        thirdPartyPrograms),
                version.equals("2021.9.1")
                        ? "https://raw.githubusercontent.com/opencv/opencv_3rdparty/"
                                + "0cc4aa06bf2bef4b05d237c69a5a96b9cd0cb85a/ippicv/"
                                + "ippicv_2021.9.1_mac_intel64_20230919_general.tgz"
                        : version.equals("2021.10.0")
                                ? "https://raw.githubusercontent.com/opencv/opencv_3rdparty/"
                                        + "0cc4aa06bf2bef4b05d237c69a5a96b9cd0cb85a/ippicv/"
                                        + "ippicv_2021.10.0_lnx_intel64_20230919_general.tgz"
                                : "https://github.com/opencv/opencv/releases/download/4.9.0/"
                                        + "opencv-4.9.0-windows.exe",
                "opencv-native",
                evidence);
    }

    private static NativeSbomComponent openClHeaders() {
        return component(
                "opencl-headers",
                "khronos",
                "OpenCL Headers",
                "1.2",
                "pkg:generic/khronos-opencl-headers@1.2",
                "MIT",
                null,
                List.of("licenses/OpenCV-OpenCL-Headers-LICENSE.txt"),
                OPENCV_SOURCE + "/3rdparty/include/opencl/1.2",
                "opencv-native",
                "OpenCL Include path: OpenCV 3rdparty/include/opencl/1.2");
    }

    private static NativeSbomComponent component(
            String id,
            String group,
            String name,
            String version,
            String purl,
            String licenseExpression,
            String licenseName,
            List<String> legalFiles,
            String sourceRef,
            String embeddedIn,
            String evidence) {
        return component(
                id,
                group,
                name,
                version,
                purl,
                licenseExpression,
                licenseName,
                legalFiles,
                sourceRef,
                embeddedIn,
                evidence,
                null);
    }

    private static NativeSbomComponent component(
            String id,
            String group,
            String name,
            String version,
            String purl,
            String licenseExpression,
            String licenseName,
            List<String> legalFiles,
            String sourceRef,
            String embeddedIn,
            String evidence,
            String sha256) {
        return new NativeSbomComponent(
                id,
                group,
                name,
                version,
                purl,
                licenseExpression,
                licenseName,
                legalFiles,
                sourceRef,
                embeddedIn,
                evidence,
                sha256);
    }

    private static String bomRef(NativeTarget target, NativeSbomComponent component) {
        return "urn:coffee-gb:native:"
                + target.id()
                + ":"
                + component.id()
                + ":"
                + component.version();
    }

    private static void field(
            StringBuilder json, int spaces, String name, String value, boolean comma) {
        json.append(" ".repeat(spaces))
                .append('"')
                .append(escape(name))
                .append("\": \"")
                .append(escape(value))
                .append('"');
        json.append(comma ? ",\n" : "\n");
    }

    private static void property(
            StringBuilder json,
            int spaces,
            String name,
            String value,
            boolean comma) {
        json.append(" ".repeat(spaces))
                .append("{\"name\": \"")
                .append(escape(name))
                .append("\", \"value\": \"")
                .append(escape(value))
                .append("\"}");
        json.append(comma ? ",\n" : "\n");
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    public record NativeSbomComponent(
            String id,
            String group,
            String name,
            String version,
            String purl,
            String licenseExpression,
            String licenseName,
            List<String> legalFiles,
            String sourceRef,
            String embeddedIn,
            String evidence,
            String sha256) {

        public NativeSbomComponent {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(purl, "purl");
            Objects.requireNonNull(legalFiles, "legalFiles");
            Objects.requireNonNull(sourceRef, "sourceRef");
            Objects.requireNonNull(evidence, "evidence");
            legalFiles = List.copyOf(legalFiles);
            if ((licenseExpression == null) == (licenseName == null)) {
                throw new IllegalArgumentException(
                        "Exactly one license expression or name is required for " + id);
            }
            if (legalFiles.isEmpty()) {
                throw new IllegalArgumentException("Legal files are required for " + id);
            }
        }
    }

    private record Property(String name, String value) {
    }
}
