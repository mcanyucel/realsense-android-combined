package com.bridgewiz.realsensecombined;

import static org.opencv.core.CvType.CV_8UC1;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

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
import java.text.DecimalFormat;
import java.util.Locale;

public class CentralGrabCutActivity extends AppCompatActivity {

    private final String TAG = "CentralGrabCutActivity";

    private ImageView imageViewColor;
    private ImageView imageViewForeground;
    private TextView txtStatus;

    private RsContext rsContext;
    private Pipeline pipeline;
    private Context appContext;
    private final Handler handler = new Handler();
    private boolean isStreaming = false;
    private boolean shouldProcess = false;
    private boolean isFrozen = false;
    private boolean shouldSave = false;

    private Colorizer colorizer;
    private Align align;

    private Mat elementSmall;
    private Mat elementLarge;

    private String saveDirectoryPath;

    private final DecimalFormat decimalFormat = new DecimalFormat("#.##");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_central_grab_cut);

        // initialize OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "onCreate: Failed to initialize OpenCV");
            finish();
        }

        saveDirectoryPath = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "CentralGrabs").getPath();
        try {
            Files.createDirectories(Paths.get(saveDirectoryPath));
        } catch (IOException e) {
            Log.e(TAG, "onCreate: Failed to create image directory", e);
        }

        appContext = getApplicationContext();
        imageViewColor = findViewById(R.id.imgCentralGrabCutColor);
        imageViewForeground = findViewById(R.id.imgCentralGrabCutForeground);
        txtStatus = findViewById(R.id.txtCentralGrabCutDistance);

        imageViewColor.setOnClickListener(view -> processImage());
        imageViewForeground.setOnClickListener(view -> saveImage());

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
     * Updates the text on the status view with the given string
     * @param text Text
     */
    private void updateStatusText(final String text) {
        runOnUiThread(()-> txtStatus.setText(text));
    }

    /**
     * Updates teh text on the status view with the given string resource id
     * @param resId Resource id
     */
    private void updateStatusText(int resId) {
        runOnUiThread(() -> txtStatus.setText(resId));
    }

    private final DeviceListener deviceListener = new DeviceListener() {
        @Override
        public void onDeviceAttach() {

        }

        @Override
        public void onDeviceDetach() {
            updateStatusText(R.string.waiting_for_connection);
            stopRsCamera();
        }
    };

    // Extract Mats outside
    Mat colorMat;
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
    Mat near;
    Mat far;
    Mat tree;
    Mat combinedBg;
    Mat bwDepthMatMaster;


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
        near = new Mat();
        far = new Mat();
        tree = new Mat();
        combinedBg = new Mat();

    }

    private final Runnable mStreaming = new Runnable() {
        @Override
        public void run() {

            if (isFrozen) {
                if (shouldSave) {
                    String imagePath = CvHelpers.createImagePath(saveDirectoryPath, "bwDepthMaster");
                    CvHelpers.SwapAndSave(imagePath, bwDepthMatMaster);
                    CvHelpers.SwapAndSave(CvHelpers.createImagePath(saveDirectoryPath, "near"), near);
                    CvHelpers.SwapAndSave(CvHelpers.createImagePath(saveDirectoryPath, "far"), far);
                    CvHelpers.SwapAndSave(CvHelpers.createImagePath(saveDirectoryPath, "combinedBg"), combinedBg);
                    CvHelpers.SwapAndSave(CvHelpers.createImagePath(saveDirectoryPath, "gcCombinedFgMask"), gcCombinedFgMask);
                    CvHelpers.SwapAndSave(CvHelpers.createImagePath(saveDirectoryPath, "gcFgdMask"), gcFgdMask);
                    CvHelpers.SwapAndSave(CvHelpers.createImagePath(saveDirectoryPath, "gcPrFgdMask"), gcPrFgdMask);
                    shouldSave = false;
                    Toast.makeText(appContext, getString(R.string.saved), Toast.LENGTH_SHORT).show();
                }
                handler.post(mStreaming);
                return;
            }

            try {
                try (FrameReleaser frameReleaser = new FrameReleaser()) {
                    FrameSet frameSet = pipeline.waitForFrames().releaseWith(frameReleaser);
                    FrameSet processedFrameSet = frameSet.applyFilter(align).releaseWith(frameReleaser);

                    VideoFrame colorFrame = processedFrameSet
                            .first(StreamType.COLOR)
                            .releaseWith(frameReleaser)
                            .as(Extension.VIDEO_FRAME);

                    colorMat = CvHelpers.VideoFrame2Mat(colorFrame);



                    DepthFrame depthFrame = processedFrameSet
                            .first(StreamType.DEPTH)
                            .releaseWith(frameReleaser)
                            .as(Extension.DEPTH_FRAME);



                    // get the depth at center, both in terms of meters
                    float distance = depthFrame.getDistance(depthFrame.getWidth()/2, depthFrame.getHeight()/2);
                    updateStatusText(String.format(Locale.US, "Mesafe: %s metre", decimalFormat.format(distance)));

                    if (shouldProcess) {
                        // dev - for checking correctness of depthFrameMat values
//                        Mat depthMat = CvHelpers.DepthFrame2Mat(depthFrame);
//                        Mat dum = new Mat();
//                        depthMat.convertTo(dum, CV_64F);
//                        double distancePx = dum.get(dum.rows()/2, dum.cols()/2)[0];
//                        double distancePxMeters = distancePx * depthFrame.getUnits();
//                        Log.d(TAG, String.format("DistancePx: %f Units: %f distance: %f", distancePx, depthFrame.getUnits(), distancePxMeters));

                        // get grayscale depth image
                        colorizer.setValue(Option.COLOR_SCHEME, 2);
                        Frame bwDepthFrame = depthFrame.applyFilter(colorizer).releaseWith(frameReleaser);

                        bwDepthMatMaster = CvHelpers.VideoFrame2Mat(bwDepthFrame.as(Extension.VIDEO_FRAME));
                        Imgproc.cvtColor(bwDepthMatMaster, bwDepthMatMaster, Imgproc.COLOR_BGR2GRAY);

                        // depth value of center in grayscale 8 bit depth image
                        double du = bwDepthMatMaster.get(bwDepthMatMaster.rows() / 2, bwDepthMatMaster.cols() / 2)[0];
                        // the differential value; any values different than center +- delta/2 will be background
                        double maxExpectedDiameter = 0.5; // meters
                        double delta = du * maxExpectedDiameter / distance / 2.0;

                        double thresholdFar = du - delta;
                        double thresholdNear = du + delta;

                        Log.d(TAG, String.format("run: far %s near %s", thresholdFar, thresholdNear));

                        near = bwDepthMatMaster.clone();
                        createMaskFromDepth(near, thresholdNear, Imgproc.THRESH_BINARY);

                        far = bwDepthMatMaster.clone();
                        createMaskFromDepth(far, thresholdFar, Imgproc.THRESH_BINARY_INV);

                        // combine near and far backgrounds
                        Core.bitwise_or(near, far, combinedBg);

                        // the foreground is the inverse of background
                        Core.bitwise_not(combinedBg, tree);

                        mask.create(near.size(), CV_8UC1);
                        mask.setTo(Scalar.all(Imgproc.GC_BGD)); // default guess is background

                        Core.compare(far, new Scalar(255), maskPrBGD, Core.CMP_EQ);
                        mask.setTo(Scalar.all(Imgproc.GC_PR_BGD), maskPrBGD);

                        Core.compare(near, new Scalar(255), maskPrBGD, Core.CMP_EQ);
                        mask.setTo(Scalar.all(Imgproc.GC_PR_BGD), maskPrBGD);

                        Core.compare(tree, new Scalar(255), maskFGD, Core.CMP_EQ);
                        mask.setTo(Scalar.all(Imgproc.GC_FGD), maskFGD);

                        Imgproc.grabCut(colorMat, mask, new Rect(), bgModel, fgModel, 1, Imgproc.GC_INIT_WITH_MASK);

                        Core.compare(mask, new Scalar(Imgproc.GC_FGD), gcFgdMask, Core.CMP_EQ);
                        Core.compare(mask, new Scalar(Imgproc.GC_PR_FGD), gcPrFgdMask, Core.CMP_EQ);
                        // Probably-Foreground mask is inaccurate??
//                        Core.bitwise_or(gcFgdMask, gcPrFgdMask, gcCombinedFgMask);

                        foreground = new Mat();
                        colorMat.copyTo(foreground, gcFgdMask);

                        try {
                            Bitmap bitmap = CvHelpers.ColorMat2BitmapNoChannelSwap(foreground);
                            runOnUiThread(()-> imageViewForeground.setImageBitmap(bitmap));
                            shouldProcess = false;
                            isFrozen = true;
                        } catch (CvException e) {
                            Log.e(TAG, "run: Conversion error", e);
                        }
                    }

                    // draw cross-hair
                    int rows = colorMat.rows();
                    int cols = colorMat.cols();
                    Imgproc.line(colorMat, new Point(0, rows/2f), new Point(cols, rows/2f), new Scalar(255,0,0), 3);
                    Imgproc.line(colorMat, new Point(cols/2f, 0), new Point(cols/2f, rows), new Scalar(255,0,0), 3);
                    Bitmap colorBitmap = CvHelpers.ColorMat2BitmapNoChannelSwap(colorMat);
                    runOnUiThread(() -> imageViewColor.setImageBitmap(colorBitmap));
                }
                handler.post(mStreaming);
            }
            catch (Exception e) {
                Log.e(TAG, "run: Streaming error", e);
            }
        }
    };

    /**
     * COnfigures and starts the Intel camera
     * @throws Exception on initialization failure
     */
    private void configAndStart() throws Exception {
        try (Config config = new Config()) {
            config.enableStream(StreamType.DEPTH, 640, 480);
            config.enableStream(StreamType.COLOR, 640, 480);
            // tyr statement is needed here to release the resources allocated by the Pipeline::start()
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
        rsContext.setDevicesChangedCallback(deviceListener);
        pipeline = new Pipeline();
        colorizer = new Colorizer();
        align = new Align(StreamType.COLOR);

        try (DeviceList list = rsContext.queryDevices()) {
            if (list.getDeviceCount() > 0) {
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
     * Synchronized function that stops the Intel camera
     */
    private synchronized void stopRsCamera() {
        if (!isStreaming) return;

        try {
            handler.removeCallbacks(mStreaming);
            pipeline.stop();
            isStreaming = false;

        }
        catch (Exception e) {
            Log.e(TAG, "stopRsCamera: Failed to stop streaming", e);
        }
    }

    /**
     * Initializes the saving sequence of the color and the foreground image for
     * the next frame
     */
    private synchronized void saveImage() {
        if (isFrozen)
            shouldSave = true;
        else
            Toast.makeText(appContext, getString(R.string.error_cannot_save_unfrozen), Toast.LENGTH_SHORT).show();
    }

    /**
     * If the system is in frozen state, resumes streaming, otherwise initializes processing
     */
    private synchronized void processImage() {
        if (isFrozen) {
            isFrozen = false;
            imageViewForeground.setImageDrawable(null);
        }
        else {
            shouldProcess = true;
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