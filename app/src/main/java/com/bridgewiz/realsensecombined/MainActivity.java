package com.bridgewiz.realsensecombined;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

public class MainActivity extends AppCompatActivity {

    private final String TAG = "MainActivity";
    private final int CAMERA_REQUEST = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // check for camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        }

        findViewById(R.id.btnPointCloudActivity).setOnClickListener(view -> {
            Intent pointCloudActivityIntent = new Intent(this, PointCloudActivity.class);
            this.startActivity(pointCloudActivityIntent);
        });

        findViewById(R.id.btnStreamActivity).setOnClickListener(view -> {
            Intent streamActivityIntent = new Intent(this, StreamActivity.class);
            this.startActivity(streamActivityIntent);
        });

        findViewById(R.id.btnProcessingActivity).setOnClickListener(view -> {
            Intent processingActivityIntent = new Intent(this, ProcessingActivity.class);
            this.startActivity(processingActivityIntent);
        });

        findViewById(R.id.btnOpenCVStreamActivity).setOnClickListener(view -> {
            Intent opencvStreamActivityIntent = new Intent (this, OpenCvActivity.class);
            this.startActivity(opencvStreamActivityIntent);
        });

        findViewById(R.id.btnAutoGrabCutActivity).setOnClickListener(view -> {
            Intent autoGrabCutActivityIntent = new Intent(this, AutoGrabCut.class);
            this.startActivity(autoGrabCutActivityIntent);
        });

        findViewById(R.id.btnCentralGrabCutActivity).setOnClickListener(view -> {
            Intent centralGrabCutActivityIntent = new Intent(this, CentralGrabCutActivity.class);
            this.startActivity(centralGrabCutActivityIntent);
        });

        findViewById(R.id.btnDistanceMaskActivity).setOnClickListener(view -> {
            Intent distanceMaskActivityIntent = new Intent(this, DistanceMaskActivity.class);
            this.startActivity(distanceMaskActivityIntent);
        });

        findViewById(R.id.btnCentralDistanceChartActivity).setOnClickListener(view -> {
            Intent centralDistanceChartActivityIntent = new Intent(this, CentralDistanceChartActivity.class);
            this.startActivity(centralDistanceChartActivityIntent);
        });

        findViewById(R.id.btnMaskAndCloudActivity).setOnClickListener(view -> {
            Intent maskAndCloudActivityIntent = new Intent(this, MaskAndCloudActivity.class);
            this.startActivity(maskAndCloudActivityIntent);
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        }
        else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }
}