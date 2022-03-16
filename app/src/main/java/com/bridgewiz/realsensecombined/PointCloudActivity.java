package com.bridgewiz.realsensecombined;

import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.intel.realsense.librealsense.DepthFrame;
import com.intel.realsense.librealsense.DeviceListener;
import com.intel.realsense.librealsense.Extension;
import com.intel.realsense.librealsense.Frame;
import com.intel.realsense.librealsense.FrameReleaser;
import com.intel.realsense.librealsense.FrameSet;
import com.intel.realsense.librealsense.GLRsSurfaceView;
import com.intel.realsense.librealsense.Pipeline;
import com.intel.realsense.librealsense.PipelineProfile;
import com.intel.realsense.librealsense.Pointcloud;
import com.intel.realsense.librealsense.Points;
import com.intel.realsense.librealsense.RsContext;
import com.intel.realsense.librealsense.StreamFormat;
import com.intel.realsense.librealsense.StreamType;
import com.intel.realsense.librealsense.VideoFrame;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PointCloudActivity extends AppCompatActivity {

    private final String TAG = "PointCloudActivity";
    private final DecimalFormat decimalFormat = new DecimalFormat("#.##");
    private TextView txtDistance;
    private Button btnSavePointCloud;
    private ProgressBar pbPointCloud;
    private TextView txtStatus;
    private EditText edtDiameter;
    private GLRsSurfaceView imgStream;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH_mm_ss", Locale.US);

    private RsContext rsContext;
    private Pointcloud pointcloud;

    private boolean shouldSavePointCloud = false;

    private String lastCloudFileName = "";

    /**
     * Thread that does the streaming
     */
    private final Thread streamingThread = new Thread(()-> {
        try {
            stream();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    });

    /**
     * Method that does the streaming
     * @throws Exception RsCamera exception
     */
    private void stream() throws Exception {
        Pipeline pipeline = new Pipeline();
        // try is required to release the resources allocated by the Pipeline::start() method
        //noinspection EmptyTryBlock
        try (PipelineProfile ignored = pipeline.start()){}

        while (!streamingThread.isInterrupted()) {
            try (FrameReleaser frameReleaser = new FrameReleaser()) {
                // Getting the current depth frame
                FrameSet frameSet = pipeline.waitForFrames().releaseWith(frameReleaser);
                Frame frame = frameSet.first(StreamType.DEPTH).releaseWith(frameReleaser);
                // calculating the central pixel depth (distance)
                DepthFrame depthFrame = frame.as(Extension.DEPTH_FRAME);
                final float centralDepth = depthFrame.getDistance(depthFrame.getWidth() / 2, depthFrame.getHeight() / 2);
                Log.d(TAG, String.format("Depth frame size: %sx%s", depthFrame.getWidth(), depthFrame.getHeight()));
                runOnUiThread(() -> txtDistance.setText("Kamera Ortasi Mesafe: " + decimalFormat.format(centralDepth)));
                imgStream.upload(frameSet);
                if (shouldSavePointCloud) {
                    runOnUiThread(() -> {
                        pbPointCloud.setVisibility(View.VISIBLE);
                        txtDistance.setText("NOKTA BULUTU KAYDEDILIYOR, BU ISLEM 1 DAKIKAYA KADAR SUREBILIR");
                        btnSavePointCloud.setEnabled(false);
                    });

                    // The point cloud always returns the same number of vertices; total pixel count (?)
                    FrameSet frameSetWithPointCloud = frameSet.applyFilter(pointcloud).releaseWith(frameReleaser);
                    Frame pointCloudFrame = frameSetWithPointCloud
                            .first(StreamType.DEPTH, StreamFormat.XYZ32F)
                            .releaseWith(frameReleaser);

                    Points cloudPoints = pointCloudFrame.as(Extension.POINTS);
                    int count = cloudPoints.getCount();
                    Log.d(TAG, "Number of vertices in point cloud: " + count);
                    float[] vertices = cloudPoints.getVertices();
                    // The vertices array has x,y,z coordinates concatenated for one vertex after another
                    File externalDocumentsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                    Date date = new Date();
                    File exportFile = new File(externalDocumentsDir, String.format(Locale.US, "%s.csv", dateFormat.format(date)));
                    try (FileWriter fileWriter = new FileWriter(exportFile, true)) {
                        for (int i = 0; i < vertices.length; i += 3) {
                            fileWriter.append(String.format(Locale.US, "%f,%f,%f\r\n", vertices[i], vertices[i + 1], vertices[i + 2]));
                        }
                    }
                    lastCloudFileName = exportFile.getName();
                    shouldSavePointCloud = false;
                    runOnUiThread(()-> {
                        btnSavePointCloud.setEnabled(true);
                        pbPointCloud.setVisibility(View.GONE);
                    });
                }
            }
        }
        pipeline.stop();
    }

    /**
     * Saves a record to 'records.csv'
     */
    private void saveRecord() {
        if (lastCloudFileName.length() == 0) {
            txtStatus.setText(R.string.no_point_cloud_saved);
        }
        else if (edtDiameter.getText().toString().length() == 0) {
            txtStatus.setText(R.string.no_distance);
        }
        else {
            txtStatus.setText(R.string.saving);
            File externalDocumentsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            File recordsFile = new File(externalDocumentsDir, "records.csv");
            try (FileWriter fileWriter = new FileWriter(recordsFile, true)) {
                fileWriter.append(String.format(Locale.US, "%s,%s\r\n", lastCloudFileName, edtDiameter.getText().toString()));
                txtStatus.setText(R.string.saved);
            } catch (IOException e) {
                txtStatus.setText(R.string.save_record_failed);
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_point_cloud);

        btnSavePointCloud = findViewById(R.id.btnPointCloudSave);
        Button btnSaveRecord = findViewById(R.id.btnSaveRecord);
        txtDistance = findViewById(R.id.txtPointCloudCentralDistance);
        txtStatus = findViewById(R.id.txtStatus);
        edtDiameter = findViewById(R.id.edtDiameter);
        pbPointCloud = findViewById(R.id.pbSavingPointCloud);
        imgStream = findViewById(R.id.imgPointCloudColor);


        btnSavePointCloud.setOnClickListener(view-> shouldSavePointCloud = true);
        btnSaveRecord.setOnClickListener(view -> saveRecord());

        RsContext.init(getApplicationContext());
        rsContext = new RsContext();
        pointcloud = new Pointcloud(StreamType.COLOR);

        rsContext.setDevicesChangedCallback(new DeviceListener() {
            @Override
            public void onDeviceAttach() {
                streamingThread.start();
            }

            @Override
            public void onDeviceDetach() {
                streamingThread.interrupt();
            }
        });

        try {
            streamingThread.start();
        }
        catch (Exception e) {
            Log.e(TAG, "onCreate: ", e);
        }
    }

    @Override
    protected void onDestroy() {
        streamingThread.interrupt();
        pointcloud.close();
        rsContext.close();
        super.onDestroy();
    }
}