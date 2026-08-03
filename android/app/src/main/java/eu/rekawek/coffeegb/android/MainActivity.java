package eu.rekawek.coffeegb.android;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;
import eu.rekawek.coffeegb.androidportable.AndroidPortabilityProbe;
import eu.rekawek.coffeegb.androidportable.KotlinPortabilityProbe;

/**
 * Permission-free Phase 0 startup probe. It intentionally contains no ROM, storage, or emulator
 * session behavior; later phases replace this with the lifecycle-owned frontend.
 */
public final class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AndroidPortabilityProbe javaProbe = new AndroidPortabilityProbe(
                "Coffee GB Android groundwork",
                AndroidPortabilityProbe.BytecodeFlavor.JAVA_RECORD_AND_SWITCH
        );
        KotlinPortabilityProbe kotlinProbe = new KotlinPortabilityProbe("Coffee GB Android groundwork");

        TextView message = new TextView(this);
        message.setGravity(Gravity.CENTER);
        message.setContentDescription("Coffee GB Android build groundwork");
        message.setText(javaProbe.description() + "\n" + kotlinProbe.description());
        setContentView(message);
    }
}
