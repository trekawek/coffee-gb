package eu.rekawek.coffeegb.core.genie;

import eu.rekawek.coffeegb.core.AddressSpace;

import java.io.Serializable;

/**
 * Immutable patch value contract.
 *
 * <p>The serialization marker is retained solely because released local snapshots embed the two
 * concrete patch records. No live owner serializes patches on a normal runtime path.
 */
public interface Patch extends Serializable {

    int getAddress();

    boolean accepts(AddressSpace addressSpace, int ramBank, boolean gbc);

    int getValue();
}
