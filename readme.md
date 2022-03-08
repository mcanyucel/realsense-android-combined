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

## CentralGrabCutActivity

This activity modifies the AutoGrabCutActivity such that the foreground is determined by the distance of the central pixel. Instead of setting the foreground as the depth pixels having values greater than 180 (i.e. closest objects), the pixels in the neighborhood of the central pixel in the grayscale depth image is used. 

The central real-world distance and its grayscale depth image (8 bpp) pixel value counterpart are used to find the 8 bit neighborhood that will constitute the near and far borders splitting the background and the foreground. The diameter that defines the foreground is assumed to be 0.5 meters, and parameterized as `maxExpectedDiameter`. This value is converted to pixel (8 bit) value by comparing the real world depth (obtained from the `DepthFrame`) and pixel depth (obtained from grayscale depth image).

Unlike the `AutoGrabActivity`, the `ProbablyForeground` mask of the `GrabCut` algorithm is neglected since it caused bad segmentation in the tests.

The application can save the images creating in the intermediate steps of the algorithm.

The processing of a single image is unusably slow, taking around 10 seconds per frame.

## DistanceMaskActivity

This activity achieves to obtain the same output of the Grab-Cut algorithm with much less computing power. It uses the ratio of the central pixel distance (depth) in real world and depth image to calculate a threshold for near and far boundaries, and uses these borders to mask out the foreground and background.

It obtains the real world depth from the `DepthFrame`, the pixel depth from the depth image created by a grayscale `Colorizer` and an `Align` filter, and calculates the pixel value of 0.5 meters as the delta distance. Any pixels that are greater or lower than central pixel distance +/- delta distance are masked out from the original `Frame` using OpenCV `Mat` constructor which accepts an existing `Mat` instance and a mask instance (of type `Mat`). The near and far masks are created with `Core.compare(...)`, `Core.bitwise_x()` functions because operator overloading (`==, ||, &&, !`) is not available in Java OpenCV API.

The speed increase compared to the central grab-cut algorithm is immense, increasing from 0.1 fps to around 1-1.2 fps.