package com.bridgewiz.realsensecombined;

import static org.opencv.core.CvType.CV_8UC1;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.intel.realsense.librealsense.Align;
import com.intel.realsense.librealsense.Colorizer;
import com.intel.realsense.librealsense.Config;
import com.intel.realsense.librealsense.DepthFrame;
import com.intel.realsense.librealsense.DeviceList;
import com.intel.realsense.librealsense.DeviceListener;
import com.intel.realsense.librealsense.Extension;
import com.intel.realsense.librealsense.Frame;
import com.intel.realsense.librealsense.FrameReleaser;
import com.intel.realsense.librealsense.FrameSet;
import com.intel.realsense.librealsense.Option;
import com.intel.realsense.librealsense.Pipeline;
import com.intel.realsense.librealsense.PipelineProfile;
import com.intel.realsense.librealsense.RsContext;
import com.intel.realsense.librealsense.StreamType;
import com.intel.realsense.librealsense.VideoFrame;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvException;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class AutoGrabCut extends AppCompatActivity {

    private final String TAG = "AutoGrabCutActivity";

    private ImageView imageView;
    private TextView txtStatus;

    private RsContext rsContext;
    private Pipeline pipeline;
    private Context appContext;
    private final Handler handler = new Handler();
    private boolean isStreaming = false;
    private boolean isContinuous = false;
    private boolean shouldProcess = false;
    private boolean isStopped = false;

    private Colorizer colorizer;
    private Align align;

    private Mat elementSmall;
    private Mat elementLarge;

    private String saveDirectoryPath;
    private boolean shouldSave = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auto_grab_cut);

        // initiate OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "onCreate: Failed to initiate OpenCV");
            finish();
        }

        saveDirectoryPath = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "AutoGrabs").getPath();
        try {
            Files.createDirectories(Paths.get(saveDirectoryPath));
        } catch (IOException e) {
            Log.e(TAG, "onCreate: Failed to create image directory", e);
        }

        appContext = getApplicationContext();
        imageView = findViewById(R.id.imgAutoGrabCut);
        txtStatus = findViewById(R.id.txtAutoGrabCutStatus);
        final TextView txtInfo = findViewById(R.id.txtAutoGrabInfo);

        ((SwitchCompat)findViewById(R.id.swhAutoGrabIsContinuous)).setOnCheckedChangeListener((compoundButton, b) -> {
            isContinuous = b;
            txtInfo.setVisibility(b ? View.INVISIBLE : View.VISIBLE);
                
        });

        findViewById(R.id.fabAutoGrabSave).setOnClickListener(view -> saveImage());

        imageView.setOnClickListener(view -> {
            if (isStopped) {
                isStopped = false;
            }
            else {
                shouldProcess = true;
            }
        });

        // The following methods require OpenCV to be loaded
        createMorphElements();
        initializeMats();
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
        pipeline.close();
    }

    /**
     * Toggles UI TextView for connection status
     * @param state True: disconnected False: Connected
     */
    private void showConnectionLabel(final boolean state) {
        runOnUiThread(()->txtStatus.setVisibility(state ? View.VISIBLE : View.GONE));
    }

    /**
     * Device listener which is called on USB connection state changes
     */
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

    // Extract Mats outside
    Mat colorMat;
    Mat near;
    Mat far;
    Mat zeroMask;
    Mat mask;
    Mat maskPrBGD;
    Mat maskFGD;
    Mat bgModel;
    Mat fgModel;
    Mat foreground;
    Mat gcFgdMask;
    Mat gcPrFgdMask;
    Mat gcCombinedFgMask;

    /**
     * Initializes Mat objects used in processing
     */
    private void initializeMats() {
        zeroMask = new Mat();
        mask = new Mat();
        maskPrBGD = new Mat();
        maskFGD = new Mat();
        bgModel = new Mat();
        fgModel = new Mat();
        foreground = new Mat();
        gcFgdMask = new Mat();
        gcPrFgdMask = new Mat();
        gcCombinedFgMask = new Mat();
    }

    /**
     * Runnable that does the processing
     */
    Runnable mStreaming = new Runnable() {
        @Override
        public void run() {
            if (isStopped) {
                if (shouldSave) {
                    try {
                        String imagePath = CvHelpers.createImagePath(saveDirectoryPath, "auto");
                        CvHelpers.SwapAndSave(imagePath, foreground);
                        shouldSave = false;
                        Toast.makeText(appContext, getString(R.string.saved), Toast.LENGTH_SHORT).show();
                    }
                    catch (Exception e) {
                        Log.e(TAG, "run: Failed to save image", e);
                    }
                }
                handler.post(mStreaming);
                return;
            }
            try {
                try (FrameReleaser frameReleaser = new FrameReleaser()) {
                    FrameSet frameSet = pipeline.waitForFrames().releaseWith(frameReleaser);
                    FrameSet processedFrameSet = frameSet
                            .applyFilter(align).releaseWith(frameReleaser);

                    VideoFrame colorFrame = processedFrameSet.first(StreamType.COLOR).releaseWith(frameReleaser).as(Extension.VIDEO_FRAME);
                    colorMat = CvHelpers.VideoFrame2Mat(colorFrame);

                    if (!isContinuous && !shouldProcess) {
                        Bitmap colorBitmap = CvHelpers.ColorMat2BitmapNoChannelSwap(colorMat);
                        runOnUiThread(()->imageView.setImageBitmap(colorBitmap));
                    }
                    else {
                        DepthFrame depthFrame = processedFrameSet.first(StreamType.DEPTH).releaseWith(frameReleaser).as(Extension.DEPTH_FRAME);
                        // create a black-and-white depth image (white near, black far)
                        colorizer.setValue(Option.COLOR_SCHEME, 2);
                        Frame bwDepthFrame = depthFrame.applyFilter(colorizer).releaseWith(frameReleaser);
                        // create near mask - assume values 180+ are near
                        near = CvHelpers.VideoFrame2Mat(bwDepthFrame.as(Extension.VIDEO_FRAME));
                        Imgproc.cvtColor(near, near, Imgproc.COLOR_BGR2GRAY);
                        createMaskFromDepth(near, 180, Imgproc.THRESH_BINARY);

                        // create far mask
                        far = CvHelpers.VideoFrame2Mat(bwDepthFrame.as(Extension.VIDEO_FRAME));
                        Imgproc.cvtColor(far, far, Imgproc.COLOR_BGR2GRAY);
                        // Note: 0 value does not indicate pixel near the camera, and requires special attention
                        // Also Mat == 0 does not work in java
                        Core.compare(far, new Scalar(0), zeroMask, Core.CMP_EQ);
                        far.setTo(new Scalar(255), zeroMask);
                        createMaskFromDepth(far, 100, Imgproc.THRESH_BINARY_INV);

                        mask.create(near.size(), CV_8UC1);
                        mask.setTo(Scalar.all(Imgproc.GC_BGD)); // Default guess is background

                        Core.compare(far, new Scalar(0), maskPrBGD, Core.CMP_EQ);
                        mask.setTo(Scalar.all(Imgproc.GC_PR_BGD), maskPrBGD); // relax this

                        Core.compare(near, new Scalar(255), maskFGD, Core.CMP_EQ);
                        mask.setTo(Scalar.all(Imgproc.GC_FGD), maskFGD);

                        // Run Grab-Cut
                        Imgproc.grabCut(colorMat, mask, new Rect(), bgModel, fgModel, 1, Imgproc.GC_INIT_WITH_MASK);

                        // Extract foreground pixel based on the refined mask from the grab-cut
                        Core.compare(mask, new Scalar(Imgproc.GC_FGD), gcFgdMask, Core.CMP_EQ);
                        Core.compare(mask, new Scalar(Imgproc.GC_PR_FGD), gcPrFgdMask, Core.CMP_EQ);

                        // Combine masks, | operator is not overloaded in java
                        Core.bitwise_or(gcFgdMask, gcPrFgdMask, gcCombinedFgMask);

                        // clear foreground image every frame
                        foreground = new Mat();
                        colorMat.copyTo(foreground, gcCombinedFgMask);

                        try {
                            Bitmap bitmap = CvHelpers.ColorMat2BitmapNoChannelSwap(foreground);
                            runOnUiThread(() -> imageView.setImageBitmap(bitmap));
                            // reset shouldProcess
                            shouldProcess = false;
                            // stop only if we are not in continuous mode
                            isStopped = !isContinuous;
                        } catch (CvException e) {
                            Log.e(TAG, "run: Conversion error", e);
                        }
                    }
                }
                handler.post(mStreaming);
            }
            catch (Exception e) {
                Log.e(TAG, "run: Streaming error", e);
            }
        }
    };

    /**
     * Configures and starts Intel camera
     * @throws Exception on initialization failure
     */
    private void configAndStart() throws Exception {
        try (Config config = new Config()) {
            config.enableStream(StreamType.DEPTH, 640, 480);
            config.enableStream(StreamType.COLOR, 640, 480);
            // try statement is needed here to release the resources by the Pipeline::start()
            //noinspection EmptyTryBlock
            try (PipelineProfile ignored = pipeline.start()) {}
        }
    }

    /**
     * Initializes the Intel camera
     */
    private void initRsCamera() {
        RsContext.init(appContext);

        rsContext = new RsContext();
        rsContext.setDevicesChangedCallback(mListener);
        pipeline = new Pipeline();
        colorizer = new Colorizer();
        align = new Align(StreamType.COLOR);

        try (DeviceList list = rsContext.queryDevices()){
            if (list.getDeviceCount() > 0) {
                showConnectionLabel(false);
                startRsCamera();
            }
        }

    }

    /**
     * Synchronized function that starts the Intel camera
     */
    private synchronized void startRsCamera() {
        if (isStreaming) return;

        try {
            configAndStart();
            isStreaming = true;
            handler.post(mStreaming);
        }
        catch (Exception e) {
            Log.e(TAG, "startRsCamera: Failed to start streaming", e);
        }
    }

    /**
     * Synchronized function that stops the Intel camera and disposes of the resources
     */
    private synchronized void stopRsCamera() {
        if (!isStreaming) return;

        try {
            isStreaming = false;
            handler.removeCallbacks(mStreaming);
            pipeline.stop();
        }
        catch (Exception e) {
            Log.e(TAG, "stopRsCamera: Failed to stop streaming", e);
        }
    }

    /**
     * Initializes the saving sequence of the image for the next frame
     */
    private synchronized void saveImage() {
        if (isContinuous)
            Toast.makeText(appContext, getString(R.string.error_cannot_save_in_continuous), Toast.LENGTH_SHORT).show();
        else {
            if (isStopped)
                shouldSave = true;
            else
                Toast.makeText(appContext, getString(R.string.error_cannot_save_unfrozen), Toast.LENGTH_SHORT).show();
        }
    }

    //region Image Processing
    /**
     * Creates the required morphological operation elements
     */
    private void createMorphElements() {
        final int erosionSize = 3;
        elementSmall = createElement(erosionSize);
        elementLarge = createElement(erosionSize * 2);
    }

    /**
     * Creates a generic square morphological operation element
     * @param elementSize One side of square element
     * @return The square element
     */
    private Mat createElement(int elementSize) {
        return Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                new Size(elementSize + 1, elementSize + 1),
                new Point(elementSize, elementSize)
        );
    }

    /**
     * Converts the given depth image (Mat) into a mask image
     * @param depth Depth image of type Mat
     * @param thresh Threshold value
     * @param threshType Threshold type
     */
    private void createMaskFromDepth(Mat depth, double thresh, int threshType) {
        Imgproc.threshold(depth, depth, thresh, 255, threshType);
        Imgproc.dilate(depth, depth, elementSmall);
        Imgproc.erode(depth, depth, elementLarge);
    }

    //endregion
}