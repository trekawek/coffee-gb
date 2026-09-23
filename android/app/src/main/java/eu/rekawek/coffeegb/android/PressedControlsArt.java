package eu.rekawek.coffeegb.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/** Renderer-owned pressed sprites; only the current orientation's atlas is decoded. */
final class PressedControlsArt {
    private static final PressedControlLayout[] CONTROLS = PressedControlLayout.values();
    private final Context context;
    private final Rect source = new Rect();
    private final RectF destination = new RectF();
    private Bitmap atlas;
    private boolean portrait;

    PressedControlsArt(Context context) {
        this.context = context;
    }

    void draw(Canvas canvas, Paint paint, SkinTransform transform, int mask) {
        if (mask == 0) {
            return;
        }
        boolean requestedPortrait = transform.skinHeight() >= transform.skinWidth();
        if (atlas == null || portrait != requestedPortrait) {
            if (atlas != null) {
                atlas.recycle();
            }
            portrait = requestedPortrait;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            atlas = BitmapFactory.decodeResource(context.getResources(), portrait
                    ? R.drawable.coffee_gb_pressed_portrait : R.drawable.coffee_gb_pressed_landscape,
                    options);
            if (atlas == null) {
                throw new IllegalStateException("Unable to load Coffee GB pressed controls");
            }
        }
        for (PressedControlLayout control : CONTROLS) {
            if (!control.active(mask)) {
                continue;
            }
            source.set(control.atlasX, control.atlasY,
                    control.atlasX + control.spriteWidth(portrait),
                    control.atlasY + control.spriteHeight(portrait));
            SkinTransform.Bounds nativeBounds = control.bounds(
                    transform.skinWidth(), transform.skinHeight());
            SkinTransform.Bounds bounds = transform.mapBounds(nativeBounds.left(), nativeBounds.top(),
                    nativeBounds.right(), nativeBounds.bottom());
            destination.set(bounds.left(), bounds.top(), bounds.right(), bounds.bottom());
            canvas.drawBitmap(atlas, source, destination, paint);
        }
    }
}
