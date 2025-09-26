package eu.rekawek.coffeegb.core.genie;

import eu.rekawek.coffeegb.core.AddressSpace;

import java.io.Serializable;

public interface Patch extends Serializable {

    int getAddress();

    boolean accepts(AddressSpace addressSpace, int ramBank, boolean gbc);

    int getValue();
}
