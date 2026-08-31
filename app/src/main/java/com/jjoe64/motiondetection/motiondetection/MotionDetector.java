package com.jjoe64.motiondetection.motiondetection;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.util.Log;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MotionDetector implements ImageAnalysis.Analyzer {

    private static final String TAG = "MotionDetector";

    private final AggregateLumaMotionDetection detector;
    private long checkInterval;
    private int minLuma;
    private MotionDetectorCallback motionDetectorCallback;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private ExecutorService cameraExecutor;
    private Camera camera;
    private ProcessCameraProvider cameraProvider;
    private final Context mContext;
    private PreviewView previewView;
    // CameraHelper.CameraType cameraType;
    public enum CameraType {
        FRONT,
        BACK
    }

    CameraType cameraType;

    private long lastCheck = 0;
    private Handler mHandler = new Handler();

    public MotionDetector(Context context, FrameLayout previewContainer, CameraType cameraType, long checkInterval, int minLuma) {
        detector = new AggregateLumaMotionDetection();
        mContext = context;
        this.checkInterval = checkInterval;
        this.minLuma = minLuma;
        this.cameraType = cameraType;
        previewView = new PreviewView(context);
        previewContainer.addView(previewView);
    }

    public void setMotionDetectorCallback(MotionDetectorCallback motionDetectorCallback) {
        this.motionDetectorCallback = motionDetectorCallback;
    }

    public void setLeniency(int l) {
        detector.setLeniency(l);
    }

    public void onResume(LifecycleOwner lifecycleOwner) {
        if (!isRunning.get()) {
            if (checkCameraHardware()) {
                cameraExecutor = Executors.newSingleThreadExecutor();
                startCamera(lifecycleOwner);
                isRunning.set(true);
            }
        }
    }

    private void startCamera(LifecycleOwner lifecycleOwner) {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(mContext);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(lifecycleOwner);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error starting the camera:" + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(mContext));
    }

    private void bindCameraUseCases(LifecycleOwner lifecycleOwner) {
        CameraSelector cameraSelector = cameraType == CameraType.FRONT ?
                CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis imageAnalyzer = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalyzer.setAnalyzer(cameraExecutor, this);

        cameraProvider.unbindAll();

        camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview, imageAnalyzer);
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        if (!isRunning.get()){
            return;
        }
        long now = System.currentTimeMillis();
        if (now-lastCheck > checkInterval) {
            lastCheck = now;

            ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
            byte[] yPlaneData = new byte[yBuffer.capacity()];
            yBuffer.get(yPlaneData);
            int[] img = ImageProcessing.decodeYUV420SPtoLuma(yPlaneData, image.getWidth(), image.getHeight());
            // check if it is too dark
            int lumaSum = 0;
            for (int i : img) {
                lumaSum += i;
            }
            if (lumaSum < minLuma) {
                if (motionDetectorCallback != null) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            motionDetectorCallback.onTooDark();
                        }
                    });
                }
            } else if (detector.detect(img, image.getWidth(), image.getHeight())) {
                // check
                if (motionDetectorCallback != null) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            motionDetectorCallback.onMotionDetected();
                        }
                    });
                }
            }
        }
        image.close();
    }

    private void runOnUiThread(Runnable runnable) {
        ContextCompat.getMainExecutor(mContext).execute(runnable);
    }

    public void onPause() {
        releaseCamera();
        isRunning.set(false);
    }

    private void releaseCamera() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }

    public interface MotionDetectorCallback {
        void onMotionDetected();

        void onTooDark();
    }

    // This is the same function as before, but it has been moved to this class
//    public static int[] decodeYUV420SPtoLuma(byte[] yuv420sp, int width, int height) {
//        final int frameSize = width * height;
//        int[] gray = new int[frameSize];
//
//        for (int j = 0, yp = 0; j < height; j++) {
//            int uvp = frameSize + (j >> 1) * width, u = 0, v = 0;
//            for (int i = 0; i < width; i++, yp++) {
//                int y = (0xff & ((int) yuv420sp[yp])) - 16;
//                if (y < 0)
//                    y = 0;
//                if ((i & 1) == 0) {
//                    v = (0xff & yuv420sp[uvp++]) - 128;
//                    u = (0xff & yuv420sp[uvp++]) - 128;
//                }
//
//                int y1192 = 1192 * y;
//                int r = (y1192 + 1634 * v);
//                int g = (y1192 - 833 * v - 400 * u);
//                int b = (y1192 + 2066 * u);
//
//                if (r < 0) r = 0; else if (r > 262143) r = 262143;
//                if (g < 0) g = 0; else if (g > 262143) g = 262143;
//                if (b < 0) b = 0; else if (b > 262143) b = 262143;
//
//                int temp = (r << 6) + ((g >> 2) << 12) + (b >> 4);
//
//                gray[yp] = (((temp >> 16) & 0xff) + ((temp >> 8) & 0xff) + (temp & 0xff)) / 3;
//            }
//        }
//        return gray;
//    }

    public boolean checkCameraHardware() {
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)){
            // this device has a camera
            return true;
        } else {
            // no camera on this device
            return false;
        }
    }
}
