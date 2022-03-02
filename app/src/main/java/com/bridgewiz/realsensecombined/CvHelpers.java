package com.bridgewiz.realsensecombined;

import static org.opencv.core.CvType.CV_8UC3;

import android.graphics.Bitmap;
import android.util.Log;

import com.intel.realsense.librealsense.VideoFrame;

import org.opencv.android.Utils;
import org.opencv.core.CvException;
import org.opencv.core.Mat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class CvHelpers {

    private static final String TAG = "CvHelpers";

    /**
     * Converts the given libsense VideoFrame (not DepthFrame) to OpenCV Mat.
     * @param frame VideoFrame instance
     * @return Mat instance (CV_8UC3)
     */
    public static Mat VideoFrame2Mat(final VideoFrame frame) {
        Mat frameMat = new Mat(frame.getHeight(), frame.getWidth(), CV_8UC3);
        final int bufferSize = (int)(frameMat.total() * frameMat.elemSize());
        byte[] dataBuffer = new byte[bufferSize];
        frame.getData(dataBuffer);
        ByteBuffer.wrap(dataBuffer).order(ByteOrder.LITTLE_ENDIAN).asReadOnlyBuffer().get(dataBuffer);
        frameMat.put(0,0, dataBuffer);
        return frameMat;
    }

    /**
     * Converts the given OpenCV Mat to Android.graphics.Bitmap
     * @param mat Mat instance (should be 8UC3)
     * @return Bitmap instance (ARGB_8888)
     */
    public static Bitmap ColorMat2BitmapNoChannelSwap(final Mat mat) throws CvException {
        Bitmap bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
        try {
            Utils.matToBitmap(mat, bitmap);
            return bitmap;
        }
        catch (CvException e) {
            Log.e(TAG, "ColorMat2BitmapNoChannelSwap: conversion error", e);
            throw e;
        }
    }
}
