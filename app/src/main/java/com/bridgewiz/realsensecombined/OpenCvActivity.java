package com.bridgewiz.realsensecombined;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.intel.realsense.librealsense.Align;
import com.intel.realsense.librealsense.Colorizer;
import com.intel.realsense.librealsense.Config;
import com.intel.realsense.librealsense.DeviceList;
import com.intel.realsense.librealsense.DeviceListener;
import com.intel.realsense.librealsense.Extension;
import com.intel.realsense.librealsense.FrameReleaser;
import com.intel.realsense.librealsense.FrameSet;
import com.intel.realsense.librealsense.HoleFillingFilter;
import com.intel.realsense.librealsense.Pipeline;
import com.intel.realsense.librealsense.PipelineProfile;
import com.intel.realsense.librealsense.RsContext;
import com.intel.realsense.librealsense.StreamType;
import com.intel.realsense.librealsense.VideoFrame;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvException;
import org.opencv.core.Mat;

import java.text.DecimalFormat;

public class OpenCvActivity extends AppCompatActivity {

    private final String TAG = "OpenCVActivity";

    private final DecimalFormat decimalFormat = new DecimalFormat("#.##");
    private TextView txtDistance;
    private TextView txtStatus;
    private ImageView imgOpenCVStreamDepth;
    private ImageView imgOpenCVStreamColor;

    private RsContext rsContext;
    private Pipeline mPipeline;
    private Context mAppContext;
    private final Handler mHander = new Handler();
    private boolean isStreaming = false;

    // required filters and helpers
    private Colorizer colorizer;
    private Align align;
    private HoleFillingFilter holeFillingFilter;
    private boolean shouldFillHoles = false;






    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_open_cv);

        // Initialize opencv
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "onCreate: Failed to initialize OpenCV!");
            finish();
        }

        mAppContext = getApplicationContext();
        txtDistance = findViewById(R.id.txtOpenCVDistance);
        imgOpenCVStreamDepth = findViewById(R.id.imgOpencvStreamDepth);
        imgOpenCVStreamColor = findViewById(R.id.imgOpencvStreamColor);
        txtStatus = findViewById(R.id.txtOpencvStatus);

        SwitchCompat fillHolesSwitch = findViewById(R.id.swhOpencvShouldFillHoles);
        fillHolesSwitch.setOnCheckedChangeListener((compoundButton, b) -> shouldFillHoles = b);
    }

    @Override
    protected void onResume() {
        super.onResume();
        initRsCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRsCamera();

        if (rsContext != null)
            rsContext.close();
        colorizer.close();
        mPipeline.close();
    }

    private void showConnectionLabel(final boolean state) {
        runOnUiThread(() -> txtStatus.setVisibility(state ? View.VISIBLE : View.GONE));
    }

    private final DeviceListener mListener = new DeviceListener() {
        @Override
        public void onDeviceAttach() {
            showConnectionLabel(false);
        }

        @Override
        public void onDeviceDetach() {
            showConnectionLabel(true);
            stopRsCamera();
        }
    };

    private void initRsCamera() {
        RsContext.init(mAppContext);

        rsContext = new RsContext();
        rsContext.setDevicesChangedCallback(mListener);

        mPipeline = new Pipeline();
        colorizer = new Colorizer();
        holeFillingFilter = new HoleFillingFilter();
        align = new Align(StreamType.COLOR);

        try (DeviceList list = rsContext.queryDevices()) {
            if (list.getDeviceCount() > 0) {
                showConnectionLabel(false);
                startRsCamera();
            }
        }
    }

    Runnable mStreaming = new Runnable() {
        @Override
        public void run() {
            try {
                try (FrameReleaser frameReleaser = new FrameReleaser()) {
                    FrameSet frameSet = mPipeline.waitForFrames().releaseWith(frameReleaser);
                    FrameSet processed;
                    if (shouldFillHoles)
                        processed = frameSet
                            .applyFilter(align).releaseWith(frameReleaser)
                            .applyFilter(holeFillingFilter).releaseWith(frameReleaser)
                            .applyFilter(colorizer).releaseWith(frameReleaser)
                            ;
                    else
                        processed = frameSet
                                .applyFilter(align).releaseWith(frameReleaser)
                                .applyFilter(colorizer).releaseWith(frameReleaser)
                                ;

                    // Acquire depth map image
                    VideoFrame depthFrame = processed.first(StreamType.DEPTH).releaseWith(frameReleaser).as(Extension.VIDEO_FRAME);
                    Mat depthMat = CvHelpers.VideoFrame2Mat(depthFrame);
                    // Acquire color image
                    VideoFrame colorFrame = processed.first(StreamType.COLOR).releaseWith(frameReleaser).as(Extension.VIDEO_FRAME);
                    Mat colorMat = CvHelpers.VideoFrame2Mat(colorFrame);

                    try {
                        Bitmap bitmapDepth = CvHelpers.ColorMat2BitmapNoChannelSwap(depthMat);
                        Bitmap bitmapColor = CvHelpers.ColorMat2BitmapNoChannelSwap(colorMat);
                        runOnUiThread(() -> {
                            imgOpenCVStreamDepth.setImageBitmap(bitmapDepth);
                            imgOpenCVStreamColor.setImageBitmap(bitmapColor);
                        });
                    }
                    catch (CvException e) {
                        Log.e(TAG, "run: conversion error", e);
                    }
                }
                mHander.post(mStreaming);
            }
            catch (Exception e) {
                Log.e(TAG, "Streaming error", e);
            }
        }
    };

    private void configAndStart() throws Exception {
        try (Config config = new Config()) {
            config.enableStream(StreamType.DEPTH, 640, 480);
            config.enableStream(StreamType.COLOR, 640, 480);
            // try statement is needed here to release the resources allocated by the Pipeline::start()
            try (PipelineProfile profile = mPipeline.start()) {}
        }
    }

    private synchronized void startRsCamera() {
        if (isStreaming) return;

        try {
            Log.d(TAG, "startRsCamera: try start streaming");
            configAndStart();
            isStreaming = true;
            mHander.post(mStreaming);
            Log.d(TAG, "startRsCamera: Streaming started");
        } catch (Exception e) {
            Log.e(TAG, "startRsCamera: Failed to start streaming", e);
        }
    }

    private synchronized void stopRsCamera() {
        if (!isStreaming) return;

        try {
            Log.d(TAG, "stopRsCamera: Try stop streaming");

            isStreaming = false;
            mHander.removeCallbacks(mStreaming);
            mPipeline.stop();

            Log.d(TAG, "stopRsCamera: Streaming stopped");
        }
        catch (Exception e) {
            Log.e(TAG, "stopRsCamera: Failed to stop streaming", e);
        }
    }


}