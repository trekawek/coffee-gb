package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertThrows;

public class LinuxPackagePolicyTest {

    @Test
    public void acceptsFreedesktopGameCategoryAndUbuntu2404DebianMetadata() throws Exception {
        LinuxPackagePolicy.verifyDesktopEntry(
                "[Desktop Entry]\n"
                        + "Type=Application\n"
                        + "Categories=Game;\n");
        LinuxPackagePolicy.verifyDebianMetadata(
                "Section: games\n"
                        + "Depends: libc6 (>= 2.34), libasound2t64 (>= 1.2.11), xdg-utils\n");
        LinuxPackagePolicy.verifyDesktopRegistration(
                "#!/bin/sh\n"
                        + "xdg-desktop-menu install "
                        + LinuxPackagePolicy.INSTALLED_DESKTOP_FILE
                        + "\n");
    }

    @Test
    public void rejectsDeploymentTokenAndLowercaseDesktopCategory() {
        assertThrows(
                IOException.class,
                () -> LinuxPackagePolicy.verifyDesktopEntry(
                        "[Desktop Entry]\nCategories=DEPLOY_BUNDLE_CATEGORY\n"));
        assertThrows(
                IOException.class,
                () -> LinuxPackagePolicy.verifyDesktopEntry(
                        "[Desktop Entry]\nCategories=games\n"));
    }

    @Test
    public void rejectsWrongDebianSectionOrPreT64AudioDependency() {
        assertThrows(
                IOException.class,
                () -> LinuxPackagePolicy.verifyDebianMetadata(
                        "Section: game\nDepends: libasound2t64\n"));
        assertThrows(
                IOException.class,
                () -> LinuxPackagePolicy.verifyDebianMetadata(
                        "Section: games\nDepends: libc6, libasound2 (>= 1.0.16)\n"));
    }

    @Test
    public void rejectsMissingForeignOrDuplicateDesktopRegistration() {
        assertThrows(
                IOException.class,
                () -> LinuxPackagePolicy.verifyDesktopRegistration("#!/bin/sh\nexit 0\n"));
        assertThrows(
                IOException.class,
                () -> LinuxPackagePolicy.verifyDesktopRegistration(
                        "xdg-desktop-menu install /tmp/coffee-gb.desktop\n"));
        assertThrows(
                IOException.class,
                () -> LinuxPackagePolicy.verifyDesktopRegistration(
                        "xdg-desktop-menu install "
                                + LinuxPackagePolicy.INSTALLED_DESKTOP_FILE
                                + "\nxdg-desktop-menu install "
                                + LinuxPackagePolicy.INSTALLED_DESKTOP_FILE
                                + "\n"));
    }
}
