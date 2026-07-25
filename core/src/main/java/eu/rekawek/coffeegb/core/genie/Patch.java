package eu.rekawek.coffeegb.core.genie;

import eu.rekawek.coffeegb.core.AddressSpace;

import java.io.Serializable;

/**
 * Historical patch compatibility contract.
 *
 * <p>This exact binary interface is retained solely because released local snapshots embed the
 * two concrete compatibility records. Normal execution uses {@link CheatPatch} and never
 * constructs this type.
 */
public interface Patch extends Serializable {

    int getAddress();

    boolean accepts(AddressSpace addressSpace, int ramBank, boolean gbc);

    int getValue();
}
