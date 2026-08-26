package eu.rekawek.coffeegb.android;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class AndroidBootstrapPreferenceTest {

    @Test
    public void missingPreferenceDefaultsToFastForward() {
        assertEquals("fast-forward", AndroidBootstrapPreference.parse(null, false));

        SharedPreferences missing = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if ("contains".equals(method.getName())) {
                        return false;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        assertEquals("fast-forward", AndroidBootstrapPreference.read(missing, "system.bootstrap"));
    }

    @Test
    public void explicitModesAndLegacyAliasesRemainStable() {
        assertEquals("skip", AndroidBootstrapPreference.parse("skip", true));
        assertEquals("fast-forward", AndroidBootstrapPreference.parse("fast_forward", true));
        assertEquals("fast-forward", AndroidBootstrapPreference.parse("fast-forward", true));
        assertEquals("fast-forward", AndroidBootstrapPreference.parse("ff", true));
        assertEquals("full", AndroidBootstrapPreference.parse("full", true));
        assertEquals("full", AndroidBootstrapPreference.parse("normal", true));
    }

    @Test
    public void malformedOrWrongTypedPreferenceFailsClosedToSkip() {
        assertEquals("skip", AndroidBootstrapPreference.parse("not-a-mode", true));
        assertEquals("skip", AndroidBootstrapPreference.parse(null, true));

        SharedPreferences wrongType = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if ("contains".equals(method.getName())) {
                        return true;
                    }
                    if ("getString".equals(method.getName())) {
                        throw new ClassCastException("stored value is not a String");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        assertEquals("skip", AndroidBootstrapPreference.read(wrongType, "system.bootstrap"));
    }
}
