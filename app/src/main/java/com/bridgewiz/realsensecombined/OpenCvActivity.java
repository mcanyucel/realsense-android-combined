package com.bridgewiz.realsensecombined;

import static org.opencv.core.CvType.CV_8UC3;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

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
import org.opencv.android.Utils;
import org.opencv.core.CvException;
import org.opencv.core.Mat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.DecimalFormat;

public class OpenCvActivity extends AppCompatActivity {

    private final String TAG = "OpenCVActivity";

    private final DecimalFormat decimalFormat = new DecimalFormat("#.##");
    private TextView txtDistance;
    private TextView txtStatus;
    private ImageView imgOpenCVStream;

    private RsContext rsContext;
    private Pipeline mPipeline;
    private Context mAppContext;
    private final Handler mHander = new Handler();
    private boolean isStreaming = false;

    // required filters
    private Colorizer colorizer;
    private Align align;
    private HoleFillingFilter holeFillingFilter;






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
        imgOpenCVStream = findViewById(R.id.imgOpencvStream);
        txtStatus = findViewById(R.id.txtOpencvStatus);
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
                    FrameSet processed = frameSet

                            .applyFilter(align).releaseWith(frameReleaser)
                            .applyFilter(holeFillingFilter).releaseWith(frameReleaser)
                            .applyFilter(colorizer).releaseWith(frameReleaser)
                            ;
                    VideoFrame depthFrame = processed.first(StreamType.DEPTH).releaseWith(frameReleaser).as(Extension.VIDEO_FRAME);



                    Mat depthMat = new Mat(depthFrame.getHeight(), depthFrame.getWidth(), CV_8UC3);
                    int size = (int)(depthMat.total() * depthMat.elemSize());
                    byte[] returnBuffer = new byte[size];
                    depthFrame.getData(returnBuffer);
                    ByteBuffer.wrap(returnBuffer).order(ByteOrder.LITTLE_ENDIAN).asReadOnlyBuffer().get(returnBuffer);
                    depthMat.put(0, 0, returnBuffer);

                    Bitmap bmp = Bitmap.createBitmap(depthMat.cols(), depthMat.rows(), Bitmap.Config.ARGB_8888);

                    try {
                        Utils.matToBitmap(depthMat, bmp);
                        runOnUiThread(() -> imgOpenCVStream.setImageBitmap(bmp));
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