package eu.rekawek.coffeegb.android;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;
import eu.rekawek.coffeegb.controller.state.StateImage;

/**
 * Permission-free Phase 1 startup probe. It validates that the real portable controller runtime
 * reaches Android without introducing ROM, storage, or session behavior prematurely.
 */
public final class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        StateImage portableFrame = new StateImage(1, 1, new int[]{0x00_88_cc});

        TextView message = new TextView(this);
        message.setGravity(Gravity.CENTER);
        message.setContentDescription("Coffee GB Android portable runtime probe");
        message.setText("Coffee GB Android portable runtime ready: " +
                String.format("#%06X", portableFrame.copyRgb()[0]));
        setContentView(message);
    }
}
