package com.bridgewiz.realsensecombined;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.intel.realsense.librealsense.Align;
import com.intel.realsense.librealsense.Colorizer;
import com.intel.realsense.librealsense.Config;
import com.intel.realsense.librealsense.DecimationFilter;
import com.intel.realsense.librealsense.DeviceList;
import com.intel.realsense.librealsense.DeviceListener;
import com.intel.realsense.librealsense.Frame;
import com.intel.realsense.librealsense.FrameReleaser;
import com.intel.realsense.librealsense.FrameSet;
import com.intel.realsense.librealsense.GLRsSurfaceView;
import com.intel.realsense.librealsense.HoleFillingFilter;
import com.intel.realsense.librealsense.Option;
import com.intel.realsense.librealsense.Pipeline;
import com.intel.realsense.librealsense.PipelineProfile;
import com.intel.realsense.librealsense.RsContext;
import com.intel.realsense.librealsense.SpatialFilter;
import com.intel.realsense.librealsense.StreamFormat;
import com.intel.realsense.librealsense.StreamType;
import com.intel.realsense.librealsense.TemporalFilter;
import com.intel.realsense.librealsense.ThresholdFilter;

public class ProcessingActivity extends AppCompatActivity {

    private static final String TAG = "ProcessingActivity";

    private Context mAppContext;
    private TextView mBackgroundText;
    private GLRsSurfaceView mGLSurfaceViewOrg;
    private GLRsSurfaceView mGLSurfaceViewProcessed;
    private boolean mIsStreaming = false;
    private final Handler mHandler = new Handler();

    private Pipeline mPipeline;

    // filters
    private Align mAlign;
    private Colorizer mColorizerOrg;
    private Colorizer mColorizerProcessed;
    private DecimationFilter mDecimationFilter;
    private HoleFillingFilter mHoleFillingFilter;
    private TemporalFilter mTemporalFilter;
    private ThresholdFilter mThresholdFilter;
    private SpatialFilter mSpatialFilter;

    private RsContext mRsContext;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_processing);

        mAppContext = getApplicationContext();
        mBackgroundText = findViewById(R.id.connectCameraText);

        mGLSurfaceViewOrg = findViewById(R.id.glSurfaceViewOrg);
        mGLSurfaceViewOrg.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        mGLSurfaceViewProcessed = findViewById(R.id.glSurfaceViewProcessed);
        mGLSurfaceViewProcessed.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mGLSurfaceViewOrg.close();
        mGLSurfaceViewProcessed.close();
    }

    @Override
    protected void onResume() {
        super.onResume();

        init();
    }
    @Override
    protected void onPause() {
        super.onPause();
        if(mRsContext != null)
            mRsContext.close();
        stop();
        mPipeline.close();
    }

    /**
     * Initializes the Intel camera and all the required filters
     */
    private void init(){
        //RsContext.init must be called once in the application lifetime before any interaction with physical RealSense devices.
        //For multi activities applications use the application context instead of the activity context
        RsContext.init(mAppContext);

        //Register to notifications regarding RealSense devices attach/detach events via the DeviceListener.
        mRsContext = new RsContext();
        mRsContext.setDevicesChangedCallback(mListener);

        mPipeline = new Pipeline();

        //init filters
        mAlign = new Align(StreamType.COLOR);
        mColorizerOrg = new Colorizer();
        mColorizerProcessed = new Colorizer();
        mDecimationFilter = new DecimationFilter();
        mHoleFillingFilter = new HoleFillingFilter();
        mTemporalFilter = new TemporalFilter();
        mThresholdFilter = new ThresholdFilter();
        mSpatialFilter = new SpatialFilter();

        //config filters
        mThresholdFilter.setValue(Option.MIN_DISTANCE, 0.1f);
        mThresholdFilter.setValue(Option.MAX_DISTANCE, 0.8f);

        mDecimationFilter.setValue(Option.FILTER_MAGNITUDE, 8);

        try(DeviceList dl = mRsContext.queryDevices()){
            if(dl.getDeviceCount() > 0) {
                showConnectLabel(false);
                start();
            }
        }
    }

    /**
     * Toggles UI TextView for connection status
     * @param state True: disconnected False: Connected
     */
    private void showConnectLabel(final boolean state){
        runOnUiThread(() -> mBackgroundText.setVisibility(state ? View.VISIBLE : View.GONE));
    }

    /**
     * Device listener which is called on USB connection state changes
     */
    private final DeviceListener mListener = new DeviceListener() {
        @Override
        public void onDeviceAttach() {
            showConnectLabel(false);
        }

        @Override
        public void onDeviceDetach() {
            showConnectLabel(true);
            stop();
        }
    };

    /**
     * Runnable that does the processing
     */
    Runnable mStreaming = new Runnable() {
        @Override
        public void run() {
            try {
                try(FrameReleaser fr = new FrameReleaser()){
                    FrameSet frames = mPipeline.waitForFrames().releaseWith(fr);
                    FrameSet orgSet = frames.applyFilter(mColorizerOrg).releaseWith(fr);
                    FrameSet processedSet = frames.applyFilter(mDecimationFilter).releaseWith(fr).
                            applyFilter(mHoleFillingFilter).releaseWith(fr).
                            applyFilter(mTemporalFilter).releaseWith(fr).
                            applyFilter(mSpatialFilter).releaseWith(fr).
                            applyFilter(mThresholdFilter).releaseWith(fr).
                            applyFilter(mColorizerProcessed).releaseWith(fr).
                            applyFilter(mAlign).releaseWith(fr);
                    try(Frame org = orgSet.first(StreamType.DEPTH, StreamFormat.RGB8).releaseWith(fr)){
                        try(Frame processed = processedSet.first(StreamType.DEPTH, StreamFormat.RGB8).releaseWith(fr)){
                            mGLSurfaceViewOrg.upload(org);
                            mGLSurfaceViewProcessed.upload(processed);
                        }
                    }
                }
                mHandler.post(mStreaming);
            }
            catch (Exception e) {
                Log.e(TAG, "streaming, error: " + e.getMessage());
            }
        }
    };

    /**
     * Configures and starts Intel camera
     * @throws Exception on initialization failure
     */
    private void configAndStart() throws Exception {
        try(Config config  = new Config())
        {
            config.enableStream(StreamType.DEPTH, 640, 480);
            config.enableStream(StreamType.COLOR, 640, 480);
            // try statement needed here to release resources allocated by the Pipeline:start() method
            //noinspection EmptyTryBlock
            try(PipelineProfile ignored = mPipeline.start(config)){}
        }
    }

    /**
     * Synchronized function that starts the Intel camera
     */
    private synchronized void start() {
        if(mIsStreaming)
            return;
        try{
            Log.d(TAG, "try start streaming");
            mGLSurfaceViewOrg.clear();
            mGLSurfaceViewProcessed.clear();
            configAndStart();
            mIsStreaming = true;
            mHandler.post(mStreaming);
            Log.d(TAG, "streaming started successfully");
        } catch (Exception e) {
            Log.d(TAG, "failed to start streaming");
        }
    }

    /**
     * Synchronized function that stops the Intel camera and disposes of the resources
     */
    private synchronized void stop() {
        if(!mIsStreaming)
            return;
        try {
            Log.d(TAG, "try stop streaming");
            mIsStreaming = false;
            mHandler.removeCallbacks(mStreaming);
            mPipeline.stop();
            Log.d(TAG, "streaming stopped successfully");
            mGLSurfaceViewOrg.clear();
            mGLSurfaceViewProcessed.clear();
        }  catch (Exception e) {
            Log.d(TAG, "failed to stop streaming");
            mPipeline = null;
        }

        mColorizerOrg.close();
        mColorizerProcessed.close();
        mAlign.close();
        mTemporalFilter.close();
        mSpatialFilter.close();
        mDecimationFilter.close();
        mHoleFillingFilter.close();
        mThresholdFilter.close();
    }
}