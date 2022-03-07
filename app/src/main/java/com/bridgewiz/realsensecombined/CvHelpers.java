package com.bridgewiz.realsensecombined;

import static org.opencv.core.CvType.CV_8UC3;

import android.graphics.Bitmap;
import android.util.Log;

import com.intel.realsense.librealsense.VideoFrame;

import org.opencv.android.Utils;
import org.opencv.core.CvException;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CvHelpers {

    private static final String TAG = "CvHelpers";
    private static final SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US);

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

    /**
     * Converts the given Mat from RGB to BGR, and then saves as the given file name
     * @param path Path to save
     * @param mat Mat instance
     */
    public static void SwapAndSave(final String path, final Mat mat) {
        Mat clone = mat.clone();
        Imgproc.cvtColor(clone, clone, Imgproc.COLOR_RGB2BGR);
        Imgcodecs.imwrite(path, clone);
    }

    /**
     * Creates a path in the external app storage to save an image.
     * @param directory External image directory path
     * @param suffix String that will be appended to image file name
     * @return Full path to save the image
     */
    public static String createImagePath(String directory, String suffix) {
        String timeString = simpleDateFormat.format(new Date());
        return String.format("%s/%s-%s.jpg", directory, timeString, suffix);
    }
}
