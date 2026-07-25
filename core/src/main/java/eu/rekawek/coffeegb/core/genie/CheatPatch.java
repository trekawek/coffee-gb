package eu.rekawek.coffeegb.core.genie;

import eu.rekawek.coffeegb.core.AddressSpace;

/** Non-serializable runtime contract for an active cheat patch. */
public interface CheatPatch {

    int getAddress();

    boolean accepts(AddressSpace addressSpace, int ramBank, boolean gbc);

    int getValue();
}
