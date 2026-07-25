package eu.rekawek.coffeegb.core.hardware;

import java.util.Objects;

/** Exact current boot-ROM selection and skip/post-boot register policy for one profile. */
public record BootSpec(
        String bootRomId,
        int authenticDivPreset,
        int postBootDivPreset,
        int postBootAf,
        int postBootBc,
        int postBootDe,
        int postBootHl,
        int cgbBootHandoffTicks) {

    public BootSpec {
        Objects.requireNonNull(bootRomId, "bootRomId");
        if (!bootRomId.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("Boot ROM ID must be lowercase ASCII: " + bootRomId);
        }
        for (int value : new int[]{authenticDivPreset, postBootDivPreset, postBootAf,
                postBootBc, postBootDe, postBootHl}) {
            if (value < 0 || value > 0xffff) {
                throw new IllegalArgumentException("Boot register/preset value is outside 16 bits");
            }
        }
        if (cgbBootHandoffTicks < 0) {
            throw new IllegalArgumentException("Boot handoff ticks cannot be negative");
        }
    }
}
