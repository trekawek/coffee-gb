package eu.rekawek.coffeegb.controller.state;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class StateCatalogJavaCompatibilityTest {

    @Test
    public void twoArgumentConstructorRemainsAvailable() {
        StateCatalog catalog = new StateCatalog(Collections.emptyList(), false);

        assertFalse(catalog.getNamedStatesTruncated());
        assertNull(catalog.getNamedStatesError());
    }
}
