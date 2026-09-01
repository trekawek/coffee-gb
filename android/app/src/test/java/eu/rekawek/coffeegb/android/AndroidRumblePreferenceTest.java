package eu.rekawek.coffeegb.android;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class AndroidRumblePreferenceTest {

    @Test
    public void preferenceSetDuringConstructionAppliesWhenTheOutputAttaches() {
        AndroidRumblePreference preference = new AndroidRumblePreference();
        FakeOutput constructedButNotAttached = new FakeOutput();

        preference.setEnabled(true);
        preference.attach(constructedButNotAttached);

        assertEquals(List.of(true), constructedButNotAttached.values);
    }

    @Test
    public void recreationRetainsTheLatestPreferenceWithoutTouchingTheDetachedOutput() {
        AndroidRumblePreference preference = new AndroidRumblePreference();
        FakeOutput first = new FakeOutput();
        preference.attach(first);
        preference.setEnabled(true);
        preference.detach(first);

        preference.setEnabled(false);
        FakeOutput replacement = new FakeOutput();
        preference.attach(replacement);

        assertEquals(List.of(false, true), first.values);
        assertEquals(List.of(false), replacement.values);
    }

    private static final class FakeOutput implements AndroidRumblePreference.Output {
        private final List<Boolean> values = new ArrayList<>();

        @Override
        public void setEnabled(boolean enabled) {
            values.add(enabled);
        }
    }
}
