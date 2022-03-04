# RealSense Combined

This application is for testing and demonstrating the use of Intel RealSense Depth Camera API Android wrappers (by itself and in conjunction with OpenCV).

## PointCloudActivity

This activity allows the user to save the point cloud as a csv file in the below format:

```
x, y, z
```

The given coordinates are in meters (float). The UI does not include any streaming; it only shows the distance of the camera to the center of the depth image.

## StreamActivity

This activity allows the user to stream the color and colorized depth images onto the UI. It uses the `librealsense.GLRsSurfaceView` control of the Intel API only, therefore the color and depth streams are given directly to this control as API-specific frames.

## ProcessingActivity

This activity allows the user to stream the heavily-processed color and colorized depth images onto the UI. It uses the `librealsense.GLRsSurfaceView` control of the Intel API only, therefore the color and depth streams are given directly to this control as API-specific frames. The processing includes:

* Decimation filter
* Hole filling filter
* Temporal filter
* Spatial filter
* Threshold filter
* Colorize filter
* Align filter

Due to all these filters, the stream is unstable and extremely slow.

## OpenCVActivity

This activity allows the user to stream the color and colorized depth images onto the UI. It uses the OpenCV library and native `ImageView`; it acquires the `Frame` objects of Intel API, then converts them to OpenCV `Mat` objects, which are later converted to native `Bitmap` objects to be displayed on the UI within a simple `ImageView`.

It also includes an optional hole filling filter and an always-on align filter.

## AutoGrabCutActivity

This activity follows the C++ sample given by Intel to apply Grab-Cut algorithm for removing the background, on an Android device. 

It has slight difference implementations, such as using `Core.compare` instead of `==`, since operator overloading of `Mat` is not available on Android. 