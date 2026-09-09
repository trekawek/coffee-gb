package eu.rekawek.coffeegb.core.performance;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.List;
import static org.junit.Assert.assertEquals;

/** Structural comparison includes private immutable component records and their array contents. */
public final class PerformanceStateAssertions {
    private PerformanceStateAssertions() {}

    public static void assertStateEquals(String path, Object expected, Object actual) {
        if (expected == null || actual == null) {
            assertEquals(path, expected, actual);
            return;
        }
        Class<?> type = expected.getClass();
        assertEquals(path + " type", type, actual.getClass());
        if (type.isArray() && type.getComponentType().isPrimitive()
                && java.util.Objects.deepEquals(expected, actual)) {
            return;
        }
        if (type.isArray()) {
            assertEquals(path + " length", Array.getLength(expected), Array.getLength(actual));
            for (int i = 0; i < Array.getLength(expected); i++) {
                assertStateEquals(path + '[' + i + ']', Array.get(expected, i), Array.get(actual, i));
            }
        } else if (expected instanceof List<?> list) {
            List<?> other = (List<?>) actual;
            assertEquals(path + " size", list.size(), other.size());
            for (int i = 0; i < list.size(); i++) {
                assertStateEquals(path + '[' + i + ']', list.get(i), other.get(i));
            }
        } else if (type.isRecord()) {
            try {
                for (RecordComponent component : type.getRecordComponents()) {
                    var method = component.getAccessor();
                    method.setAccessible(true);
                    assertStateEquals(path + '.' + component.getName(), method.invoke(expected),
                            method.invoke(actual));
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Cannot compare component " + path, e);
            }
        } else {
            assertEquals(path, expected, actual);
        }
    }
}
