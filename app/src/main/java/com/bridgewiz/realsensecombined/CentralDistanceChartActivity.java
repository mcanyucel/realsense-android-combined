package com.bridgewiz.realsensecombined;

import static org.opencv.core.CvType.CV_8UC1;
import static org.opencv.core.CvType.CV_8UC3;

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
import com.intel.realsense.librealsense.Config;
import com.intel.realsense.librealsense.DepthFrame;
import com.intel.realsense.librealsense.DeviceList;
import com.intel.realsense.librealsense.DeviceListener;
import com.intel.realsense.librealsense.Extension;
import com.intel.realsense.librealsense.FrameReleaser;
import com.intel.realsense.librealsense.FrameSet;
import com.intel.realsense.librealsense.Pipeline;
import com.intel.realsense.librealsense.PipelineProfile;
import com.intel.realsense.librealsense.RsContext;
import com.intel.realsense.librealsense.StreamType;
import com.intel.realsense.librealsense.VideoFrame;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvException;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class CentralDistanceChartActivity extends AppCompatActivity {

    private final String TAG = "CentralDistanceChartActivity";

    private ImageView imageViewColor;
    private ImageView imageViewChart;
    private TextView txtStatus;

    private RsContext rsContext;
    private Pipeline pipeline;
    private Context appContext;
    private final Handler handler = new Handler();

    private boolean isStreaming = false;
    private boolean isFrozen = false;
    private boolean shouldSave = false;

    private Align align;

    private String saveDirectoryPath;

    private final DecimalFormat decimalFormat = new DecimalFormat("#.##");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_central_distance_chart);

        // initialize OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "onCreate: Failed to initialize OpenCV");
            finish();
        }

        saveDirectoryPath = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "CentralDistanceChart").getPath();

        try {
            Files.createDirectories(Paths.get(saveDirectoryPath));
        } catch (IOException e) {
            Log.e(TAG, "onCreate: Failed to create image directory", e);
        }

        appContext = getApplicationContext();
        imageViewColor = findViewById(R.id.imgCentralDistanceChartColor);
        imageViewChart = findViewById(R.id.imgCentralDistanceChartChart);
        txtStatus = findViewById(R.id.txtCentralDistanceChartDistance);

        imageViewColor.setOnClickListener(view -> freezeImage());
        imageViewChart.setOnClickListener(view -> saveImage());

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

    }

    /**
     * Updates the text on the status view with the given string
     * @param text Text
     */
    private void updateStatusText(final String text) {
        runOnUiThread(()-> txtStatus.setText(text));
    }

    /**
     * Updates the text on the status view with the given string resource id
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

    private Mat colorMat;
    private Mat chartMat;

    List<MatOfPoint> points = new ArrayList<>();


    private void initializeMats() {

    }

    private final Runnable mStreaming = new Runnable() {
        @Override
        public void run() {
            if (isFrozen) {
                if (shouldSave) {
                    CvHelpers.SwapAndSave(CvHelpers.createImagePath(saveDirectoryPath, "color"), colorMat);
                    CvHelpers.Save(CvHelpers.createImagePath(saveDirectoryPath, "chart"), chartMat);
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

                    int depthWidth = depthFrame.getWidth();
                    int halfDepthHeight = depthFrame.getHeight() / 2;


                    // get the depth at the center in meters
                    float centralDistance = depthFrame.getDistance(depthWidth / 2, halfDepthHeight);
                    updateStatusText(String.format(Locale.US, "Mesafe: %s metre", decimalFormat.format(centralDistance)));

                    // create central depth chart

                    // get mid-height distance values
                    ArrayList<Float> distanceList = new ArrayList<>(depthWidth);
                    for (int i = 0; i < depthWidth; i++) {
                        distanceList.add(depthFrame.getDistance(i, halfDepthHeight));
                    }

                    float maxDistance = (float) Collections.max(distanceList);
                    // assume 10 px margin at chart top, left, and right
                    int chartHeight = 600;
                    float scaleY = (chartHeight - 10) / maxDistance;
                    int chartWidth = 800;
                    float scaleX = (chartWidth - 20) / ((float) depthWidth);

                    chartMat = new Mat(chartHeight, chartWidth, CV_8UC3);
                    chartMat.setTo(Scalar.all(255));
                    points.clear();
                    MatOfPoint polyline = new MatOfPoint();

                    List<Point> polyLinePoints = new ArrayList<>(depthWidth);
                    List<MatOfPoint> polyLinePointsList = new ArrayList<>(1);

                    for (int i = 0; i < depthWidth; i++) {
                        float d = distanceList.get(i);
                        polyLinePoints.add(new Point(10 + i * scaleX, (chartHeight - 10) - d * scaleY));
                    }

                    polyline.fromList(polyLinePoints);
                    polyLinePointsList.add(polyline);
                    Imgproc.polylines(chartMat, polyLinePointsList, false, Scalar.all(0),2);
                    Imgproc.line(chartMat, new Point(chartWidth / 2.0, 0), new Point(chartWidth / 2.0, chartHeight), new Scalar(255, 0, 0), 4);

                    try {
                        Bitmap chartBitmap = CvHelpers.ColorMat2BitmapNoChannelSwap(chartMat);
                        runOnUiThread(() -> imageViewChart.setImageBitmap(chartBitmap));
                    } catch (CvException cve) {
                        Log.e(TAG, "run: Conversion error on chart", cve);
                    }

                    // draw cross-hair
                    try  {
                        int rows = colorMat.rows();
                        int cols = colorMat.cols();
                        Imgproc.line(colorMat, new Point(0, rows/2f), new Point(cols, rows/2f), new Scalar(255,0,0), 3);
                        Imgproc.line(colorMat, new Point(cols/2f, 0), new Point(cols/2f, rows), new Scalar(255,0,0), 3);
                        Bitmap colorBitmap = CvHelpers.ColorMat2BitmapNoChannelSwap(colorMat);
                        runOnUiThread(() -> imageViewColor.setImageBitmap(colorBitmap));
                    } catch (CvException cve) {
                        Log.e(TAG, "run: Conversion error on color", cve);
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
     * Configures and starts the Intel camera
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
     * Toggles frozen status
     */
    private synchronized void freezeImage() {
        if (isStreaming) {
            isFrozen = !isFrozen;
        }
    }
}