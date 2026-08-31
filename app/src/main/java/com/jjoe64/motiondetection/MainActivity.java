package com.jjoe64.motiondetection;

import android.content.Context;
import android.os.Bundle;
import android.os.Vibrator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.jjoe64.motiondetection.motiondetection.MotionDetector;

public class MainActivity extends AppCompatActivity {

    private MotionDetector motionDetector;
    private TextView txtStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtStatus = findViewById(R.id.txtStatus);
        FrameLayout previewContainer = findViewById(R.id.previewContainer);

        // Initialize the MotionDetector
        motionDetector = new MotionDetector(
                this,
                previewContainer,
                MotionDetector.CameraType.BACK, // Choose FRONT or BACK camera
                500, // checkInterval (e.g., 500ms)
                1000 // minLuma
        );

        // Set the MotionDetectorCallback
        motionDetector.setMotionDetectorCallback(new MotionDetector.MotionDetectorCallback() {
            @Override
            public void onMotionDetected() {
                runOnUiThread(() -> {
                    Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                    if (v != null) {
                        v.vibrate(80);
                    }
                    txtStatus.setText("Motion detected");
                });
            }

            @Override
            public void onTooDark() {
                runOnUiThread(() -> {
                    txtStatus.setText("Too dark here");
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        motionDetector.onResume(this); // Pass the LifecycleOwner (this activity)
    }

    @Override
    protected void onPause() {
        super.onPause();
        motionDetector.onPause();
    }
}
