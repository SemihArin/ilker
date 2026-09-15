package com.ilker.opendrive;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

public class MainActivity extends Activity implements SensorEventListener {

    private GameView view;
    private SensorManager sensorManager;
    private Sensor accelerometer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        view = new GameView(this);
        setContentView(view);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
    }

    private void goFullscreen() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullscreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        goFullscreen();
        view.onResume();
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        view.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        view.getGameRenderer().release();
        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        // Rotate the device reading into screen space so tilt steering means
        // the same thing whichever way round the phone is held.
        int rotation = Surface.ROTATION_0;
        if (getWindowManager() != null && getWindowManager().getDefaultDisplay() != null) {
            rotation = getWindowManager().getDefaultDisplay().getRotation();
        }
        float ax = event.values[0];
        float ay = event.values[1];
        float screenRight;
        switch (rotation) {
            case Surface.ROTATION_90:
                screenRight = -ay;
                break;
            case Surface.ROTATION_180:
                screenRight = -ax;
                break;
            case Surface.ROTATION_270:
                screenRight = ay;
                break;
            default:
                screenRight = ax;
                break;
        }
        // Dropping the right-hand edge of the screen steers right.
        view.getGameRenderer().setTiltRaw(-screenRight);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
