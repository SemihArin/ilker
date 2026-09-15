package com.ilker.opendrive;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

/** Surface that owns the GL context and forwards multi-touch to the renderer. */
public class GameView extends GLSurfaceView {

    private static final int MAX_POINTERS = 10;

    private final GameRenderer renderer;
    private final float[] xs = new float[MAX_POINTERS];
    private final float[] ys = new float[MAX_POINTERS];

    public GameView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 0, 24, 0);
        setPreserveEGLContextOnPause(true);
        renderer = new GameRenderer(context);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    public GameRenderer getGameRenderer() {
        return renderer;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int count = 0;

        if (action != MotionEvent.ACTION_CANCEL && action != MotionEvent.ACTION_UP) {
            int liftedIndex = (action == MotionEvent.ACTION_POINTER_UP)
                    ? event.getActionIndex() : -1;
            int pointers = event.getPointerCount();
            for (int i = 0; i < pointers && count < MAX_POINTERS; i++) {
                if (i == liftedIndex) continue;
                xs[count] = event.getX(i);
                ys[count] = event.getY(i);
                count++;
            }
        }

        renderer.setTouches(xs, ys, count);
        return true;
    }

    @Override
    public void onPause() {
        renderer.onPause();
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        renderer.onResume();
    }
}
