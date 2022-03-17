package com.bridgewiz.realsensecombined;

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
import com.intel.realsense.librealsense.Pointcloud;
import com.intel.realsense.librealsense.Points;
import com.intel.realsense.librealsense.RsContext;
import com.intel.realsense.librealsense.StreamFormat;
import com.intel.realsense.librealsense.StreamType;
import com.intel.realsense.librealsense.VideoFrame;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class MaskAndCloudActivity extends AppCompatActivity {

    private final String TAG = "MaskAndCloudActivity";

    private ImageView imageViewColor;
    private ImageView imageViewForeground;
    private TextView txtDistance;
    private TextView txtDiameter;
    private TextView getTxtDiameterAlt;

    private RsContext rsContext;
    private Pipeline pipeline;
    private Context appContext;
    private final Handler handler = new Handler();

    private boolean isStreaming = false;
    private boolean shouldProcess = false;
    private boolean isFrozen = false;
    private boolean shouldSave = false;

    private Align align;
    private Pointcloud pointcloud;
    private Colorizer colorizer;

    private String saveDirectoryPathImage;
    private String saveDirectoryPathDocument;
    private float lastDiameter = Float.NaN;
    private float lastDiameterAlt = Float.NaN;
    private float lastDistance = -1;
    private final DecimalFormat decimalFormat = new DecimalFormat("#.##", new DecimalFormatSymbols(Locale.US));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mask_and_cloud);

        // initialize OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "onCreate: Failed to initialize OpenCV");
            finish();
        }

        saveDirectoryPathImage = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "MaskAndCloud").getPath();
        saveDirectoryPathDocument = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),"MaskAndCloud").getPath();

        try {
            Files.createDirectories(Paths.get(saveDirectoryPathImage));
            Files.createDirectories(Paths.get(saveDirectoryPathDocument));
        } catch (IOException e) {
            Log.e(TAG, "onCreate: Failed to create image directory", e);
        }

        appContext = getApplicationContext();
        imageViewColor = findViewById(R.id.imgMaskAndCloudColor);
        imageViewForeground = findViewById(R.id.imgMaskAndCloudForeground);
        txtDiameter = findViewById(R.id.txtMaskAndCloudDiameter);
        txtDistance = findViewById(R.id.txtMaskAndCloudDistance);
        getTxtDiameterAlt = findViewById(R.id.txtMaskAndCloudDiameterAlt);

        imageViewColor.setOnClickListener(view -> processImage());
        imageViewForeground.setOnClickListener(view -> saveImage());

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

        pipeline.close();
        align.close();
        pointcloud.close();
    }

    /**
     * Updates the text on the given text view using the given string resource id which should
     * have a single format specifier
     * @param textView Text view instance
     * @param resId String resource id
     * @param value Parameter value
     */
    private void updateLabel(final TextView textView, final int resId, final float value) {
        runOnUiThread(()->textView.setText(getString(resId, value)));
    }

    /**
     * Updates the text on the given text view using the given string resource id
     * @param textView Text view instance
     * @param resId String resource id
     */
    private void updateLabel(final TextView textView, final int resId) {
        runOnUiThread(()->textView.setText(getText(resId)));
    }

    /**
     * Device listener used to track camera connection status
     */
    private final DeviceListener deviceListener = new DeviceListener() {
        @Override
        public void onDeviceAttach() {
            // TODO should call startCamera here??
        }

        @Override
        public void onDeviceDetach() {
            updateLabel(txtDistance, R.string.waiting_for_connection);
        }
    };

    private Mat colorMat;
    private Mat foregroundMat;
    private Mat farMask;
    private Mat nearMask;
    private Mat combinedMask;

    private void initializeMats() {
        farMask = new Mat();
        nearMask = new Mat();
        combinedMask = new Mat();
    }

    /**
     * The main runnable that does all the work
     */
    private final Runnable mStreaming = new Runnable() {
        @Override
        public void run() {
            if (isFrozen) {
                if (shouldSave) {
                    Date date = new Date();
                    String lastFileName = CvHelpers.createImagePath(saveDirectoryPathImage, "original", date);
                    CvHelpers.SwapAndSave(lastFileName, colorMat);
                    CvHelpers.SwapAndSave(CvHelpers.createImagePath(saveDirectoryPathImage, "foreground", date), foregroundMat);
                    saveRecord(CvHelpers.simpleDateFormat.format(date), lastDistance);

                    shouldSave = false;
                    Toast.makeText(appContext, getString(R.string.saved_with_placeholder, CvHelpers.simpleDateFormat.format(date)), Toast.LENGTH_LONG).show();
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


                    // get the depth at the center in meters
                    DepthFrame depthFrame = processedFrameSet
                            .first(StreamType.DEPTH)
                            .releaseWith(frameReleaser)
                            .as(Extension.DEPTH_FRAME);
                    float distance = depthFrame.getDistance(depthFrame.getWidth()/2, depthFrame.getHeight()/2);
                    lastDistance = distance;
                     updateLabel(txtDistance, R.string.distance_with_placeholder, distance);

                    if (shouldProcess) {
                        // get grayscale depth image
                        colorizer.setValue(Option.COLOR_SCHEME, 2);
                        Frame bwDepthFrame = depthFrame.applyFilter(colorizer).releaseWith(frameReleaser);

                        Mat bwDepthMatMaster = CvHelpers.VideoFrame2Mat(bwDepthFrame.as(Extension.VIDEO_FRAME));
                        Imgproc.cvtColor(bwDepthMatMaster, bwDepthMatMaster, Imgproc.COLOR_BGR2GRAY);

                        // depth value at the center in the grayscale 8bit depth image
                        int centerY = depthFrame.getHeight() / 2;
                        int centerX = depthFrame.getWidth() / 2;
                        double du = bwDepthMatMaster.get(bwDepthMatMaster.rows() / 2, bwDepthMatMaster.cols() / 2)[0];

                        // the differential limit; any values outside center +- delta will be erased
                        final double maxExpectedDiameter = 0.5; // meters
                        // scale maxExpectedDiameter to 8bit pixel values based on distance
                        double delta = du * maxExpectedDiameter / distance / 2;

                        double thresholdFar = du - delta;
                        double thresholdNear = du + delta;

                        // We need {!(near OR far)} which is equivalent to {!near AND !far}, which is shorter
                        // This is the reason CMP_GT and CMP_LT are in reverse
                        Core.compare(bwDepthMatMaster, Scalar.all(thresholdFar), farMask, Core.CMP_GT);
                        Core.compare(bwDepthMatMaster, Scalar.all(thresholdNear), nearMask, Core.CMP_LT);
                        Core.bitwise_and(farMask, nearMask, combinedMask);

                        foregroundMat = new Mat();
                        colorMat.copyTo(foregroundMat, combinedMask);

                        // dev: verify point cloud and depth image values
                        FrameSet frameSetPointCloud = processedFrameSet.applyFilter(pointcloud).releaseWith(frameReleaser);
                        Frame pointCloudFrame = frameSetPointCloud
                                .first(StreamType.DEPTH, StreamFormat.XYZ32F)
                                .releaseWith(frameReleaser);

                        Points cloudPoints = pointCloudFrame.as(Extension.POINTS);
                        int count = cloudPoints.getCount();
                        Log.d(TAG, "run: Number of vertices in point cloud: " + count);


                        float[] vertices = cloudPoints.getVertices();
                        /* for indexing of vertices with respect to depth image, see
                         *  https://github.com/IntelRealSense/librealsense/issues/9340
                         *  for pixel (i,j) the coordinates starting index is
                         * (j * (depthFrameWidth) + i) * 3
                         * This is the x coordinate; y and z coordinates are +1 and +2 away
                         * from this value.
                         */

                        /*
                         * Dev-test: the following code gets the middle distance from point cloud
                         * to compare it with the distance obtained above
                         */
//                        int midIndex = (centerY * depthFrame.getWidth() + centerX) * 3;
//                        float x = vertices[midIndex];
//                        float y = vertices[midIndex + 1];
//                        float z = vertices[midIndex + 2];
//                        Log.d(TAG, String.format("run: Cloud points coordinates: %s %s %s", x, y, z));

                        /* APPROACH 1
                         * Calculate tree edges from the foreground image, then use these edge
                         * pixel coordinates for seeking the 3D coordinates inside the point cloud
                         */

                        // in the mid-height, seek the first zeroes from center to outwards in x
                        int leftIndex = -1;
                        for (int i = centerX; i >=0 ; i--) {
                            double[] channels = foregroundMat.get(centerY, i);
                            if (Arrays.stream(channels).sum() == 0) {
                                leftIndex = i + 1;
                                break;
                            }
                        }

                        int rightIndex = -1;
                        for (int i = centerX; i < foregroundMat.cols(); i++) {
                            double [] channels = foregroundMat.get(centerY, i);
                            if (Arrays.stream(channels).sum() == 0) {
                                rightIndex = i - 1;
                                break;
                            }
                        }

                        int vertexYIndex = centerY * depthFrame.getWidth();

                        if (leftIndex == -1 || rightIndex == -1) {
                             updateLabel(txtDiameter, R.string.failed_to_find_tree_edge);
                        }
                        else {
                            int leftEdgeVertexIndex = (vertexYIndex + leftIndex) * 3;
                            int rightEdgeVertexIndex = (vertexYIndex + rightIndex) * 3;

                            Log.d(TAG, String.format("run: left and right pixel indices %d %d", leftIndex, rightIndex));
                            Log.d(TAG, String.format("run: left and right vertex indices %d %d", leftEdgeVertexIndex, rightEdgeVertexIndex));

                            float leftEdgeX = vertices[leftEdgeVertexIndex];
                            float leftEdgeY = vertices[leftEdgeVertexIndex + 1];
                            float leftEdgeZ = vertices[leftEdgeVertexIndex + 2];

                            float rightEdgeX = vertices[rightEdgeVertexIndex];
                            float rightEdgeY = vertices[rightEdgeVertexIndex + 1];
                            float rightEdgeZ = vertices[rightEdgeVertexIndex + 2];

                            Log.d(TAG, String.format("run: Left Edge Point: %f %f %f", leftEdgeX, leftEdgeY, leftEdgeZ));
                            Log.d(TAG, String.format("run: Right Edge Point: %f %f %f", rightEdgeX, rightEdgeY, rightEdgeZ));

                            float diameterRaw = (rightEdgeX - leftEdgeX) * 100; // centimeters
                            lastDiameter = diameterRaw;
                            updateLabel(txtDiameter, R.string.diameter_with_placeholder, diameterRaw);
                        }

                        /*
                         * APPROACH 2: We already have point cloud; therefore seek the tree edge
                         * points directly within the point cloud array instead of first finding
                         * it in the foreground image and then translating the pixel coordinates.
                         * We still need the foreground image for UI and UX
                         */

                        int midIndex = (centerY * depthFrame.getWidth() + centerX) * 3;

                        double thresholdNearZ = distance - maxExpectedDiameter;
                        double thresholdFarZ = distance + maxExpectedDiameter;

                        float leftX = Float.NaN;
                        float rightX = Float.NaN;
                        // seek half of image width
                        /* NOTE
                         * Having branches inside loops is suboptimal, however, this loop is
                         * expected be run for a maximum of 320 times (most of the time around 50-60)
                         * therefore a single loop with branches is used instead of having two loops.
                         */
                        for (int i = 1; i < depthFrame.getWidth() / 2 ; i++) {
                            // left search
                            if (Float.isNaN(leftX)) {
                                int leftZIndex = (midIndex - 1) - (i * 3);
                                float leftZ = vertices[leftZIndex];
                                if (leftZ == 0 || leftZ < thresholdNearZ || leftZ > thresholdFarZ) {
                                    int leftXIndex = leftZIndex + 1; // remember the order is x,y,z,x,y,z,x,y,z,x...
                                    leftX = vertices[leftXIndex];
                                }
                            }
                            // right search
                            if (Float.isNaN(rightX)) {
                                int rightZIndex = (midIndex + 2) + (i * 3);
                                float rightZ = vertices[rightZIndex];
                                if (rightZ == 0 || rightZ < thresholdNearZ || rightZ > thresholdFarZ) {
                                    int rightXIndex = rightZIndex - 5; // remember the order is x,y,z,x,y,z,x,y,z,x...
                                    rightX = vertices[rightXIndex];
                                }
                            }
                            if (!Float.isNaN(leftX) && !Float.isNaN(rightX))
                                break;
                        }

                        if (Float.isNaN(leftX) || Float.isNaN(rightX)) {
                            updateLabel(getTxtDiameterAlt, R.string.failed_to_find_tree_edge);
                        }
                        else {
                            float diameterRaw = (rightX - leftX) * 100; // centimeters
                            lastDiameterAlt = diameterRaw;
                            updateLabel(getTxtDiameterAlt, R.string.diameter_with_placeholder, diameterRaw);
                        }

                        try {
                            Bitmap bitmap = CvHelpers.ColorMat2BitmapNoChannelSwap(foregroundMat);
                            runOnUiThread(()->imageViewForeground.setImageBitmap(bitmap));
                            shouldProcess = false;
                            isFrozen = true;
                        }
                        catch (Exception e) {
                            Log.e(TAG, "run: Conversion error", e);
                        }
                    }
                    // Draw cross-hair to colorMat and update UI
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
     * Saves the last measurement into records.csv in the activity-specific folder
     * @param lastFileName Last time-stamp that is used to save the image files
     * @param distance Last measured distance
     */
    private void saveRecord(String lastFileName, float distance) {
        File recordsFile = new File(saveDirectoryPathDocument, "records.csv");
        try (FileWriter fileWriter = new FileWriter(recordsFile, true)) {
            fileWriter.append(
                    String.format(Locale.US, "%s,%s,%s,%s\r\n"
                            ,lastFileName,
                            decimalFormat.format(distance),
                            decimalFormat.format(lastDiameter),
                            decimalFormat.format(lastDiameterAlt)));
        }
        catch (IOException e) {
            Log.e(TAG, "saveRecord: Failed to save record", e);
            Toast.makeText(appContext, getString(R.string.save_record_failed), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Configures and starts the intel camera pipeline
     * @throws Exception on initialization failure
     */
    private void configAndStart() throws Exception {
        try (Config config = new Config()) {
            config.enableStream(StreamType.DEPTH, 640, 480);
            config.enableStream(StreamType.DEPTH, 640, 480);
            // try statement is needed here to release resources allocated by the Pipeline::start
            //noinspection EmptyTryBlock
            try (PipelineProfile ignored = pipeline.start()) {}
        }
    }

    private void initRsCamera() {
        RsContext.init(appContext);

        rsContext = new RsContext();
        rsContext.setDevicesChangedCallback(deviceListener);
        pipeline = new Pipeline();
        align = new Align(StreamType.COLOR); // TODO align to depth or color?
        pointcloud = new Pointcloud(StreamType.DEPTH);
        colorizer = new Colorizer();

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
     * Initializes the saving sequence of the measurement for the next frame.
     */
    private synchronized void saveImage() {
        if (isFrozen)
            shouldSave = true;
        else
            Toast.makeText(appContext, getString(R.string.error_cannot_save_unfrozen), Toast.LENGTH_SHORT).show();
    }

    /**
     * If the system is in frozen state, resumes streaming; otherwise initialize the
     * processing sequence.
     */
    private synchronized void processImage() {
        if (isFrozen) {
            isFrozen = false;
            imageViewForeground.setImageDrawable(null);
            updateLabel(txtDiameter, R.string.waiting_for_process);
            updateLabel(getTxtDiameterAlt, R.string.waiting_for_process);
        }
        else {
            shouldProcess = true;
        }
    }
}