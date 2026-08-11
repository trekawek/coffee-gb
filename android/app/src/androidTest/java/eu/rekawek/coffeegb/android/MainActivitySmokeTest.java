package eu.rekawek.coffeegb.android;

import android.app.Instrumentation;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/** Exercises the bound runtime through Activity recreation and a visibility transition. */
@RunWith(AndroidJUnit4.class)
public class MainActivitySmokeTest {

    @Test
    public void launchesRecreatesAndBackgroundsWithoutStartingACameraOrGame() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.recreate();
        }
    }

    @Test
    public void systemBackClosesRootMenuBeforeFinishingActivity() throws IOException {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                Button menu = menuButton(activity);
                assertEquals("Open Coffee GB menu", menu.getContentDescription().toString());
                menu.performClick();
                assertEquals("Close Coffee GB menu", menu.getContentDescription().toString());
            });
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());

            sendSystemBack(instrumentation);

            scenario.onActivity(activity -> {
                assertFalse(activity.isFinishing());
                assertFalse(activity.isDestroyed());
                assertEquals("Open Coffee GB menu",
                        menuButton(activity).getContentDescription().toString());
            });
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());

            sendSystemBack(instrumentation);
            waitForState(scenario, Lifecycle.State.DESTROYED, instrumentation);
        }
    }

    private static void sendSystemBack(Instrumentation instrumentation) throws IOException {
        ParcelFileDescriptor result = instrumentation.getUiAutomation()
                .executeShellCommand("input keyevent 4");
        try (ParcelFileDescriptor.AutoCloseInputStream stream =
                     new ParcelFileDescriptor.AutoCloseInputStream(result)) {
            byte[] buffer = new byte[64];
            while (stream.read(buffer) != -1) {
                // Waiting for EOF ensures the shell input command completed before assertions.
            }
        }
        instrumentation.waitForIdleSync();
    }

    private static Button menuButton(MainActivity activity) {
        Button button = findButton(activity.getWindow().getDecorView());
        assertNotNull("menu button", button);
        return button;
    }

    private static Button findButton(View view) {
        if (view instanceof Button button) {
            return button;
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                Button button = findButton(group.getChildAt(index));
                if (button != null) {
                    return button;
                }
            }
        }
        return null;
    }

    private static void waitForState(ActivityScenario<?> scenario, Lifecycle.State expected,
            Instrumentation instrumentation) {
        long deadline = SystemClock.uptimeMillis() + 3_000L;
        while (scenario.getState() != expected && SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync();
            SystemClock.sleep(25L);
        }
        assertEquals(expected, scenario.getState());
    }
}
