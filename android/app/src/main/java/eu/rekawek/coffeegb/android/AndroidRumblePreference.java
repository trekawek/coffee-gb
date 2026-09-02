package eu.rekawek.coffeegb.android;

import java.util.Objects;

/** Atomically retains the host preference while Android rumble outputs are replaced. */
final class AndroidRumblePreference {

    interface Output {
        void setEnabled(boolean enabled);
    }

    private boolean enabled;
    private Output output;

    synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (output != null) {
            output.setEnabled(enabled);
        }
    }

    synchronized boolean enabledForTesting() {
        return enabled;
    }

    synchronized void attach(Output output) {
        Output checked = Objects.requireNonNull(output, "output");
        checked.setEnabled(enabled);
        this.output = checked;
    }

    synchronized void detach(Output output) {
        if (this.output == output) {
            this.output = null;
        }
    }
}
