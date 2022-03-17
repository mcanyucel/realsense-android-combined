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

## CentralDistanceChartActivity

This activity achieves to obtain the line chart that exhibits the distance values along the x axis (horizontal) on the mid-height of the depth image. It displays the `COLOR` stream and the depth chart on the UI. 

The chart is drawn using OpenCV as an image using the `polylines` function.

## MaskAndCloudActivity

This activity enables real measurements by combining the distance mask approach with the point cloud. It implements two approaches for finding the real-world value of the diameter.

The activity displays the color stream, foreground image, calculated distance in the color stream center, and the diameters calculated by the two approaches. It can save the color and foreground image as jpeg files into the external Pictures folder together with the measurement data (with the file names of the saved images, distance, two diameters) into the external Documents folder.

**NOTE**: All two approaches assume that the tree is vertical.

### Combining ColorFrame and PointCloud

In this approach, the color frame is segmented into background and foreground sections using the technique explained in the *DistanceMaskActivity*. Then the tree edge lines on the left and the right side are searched by starting at the center of the image and going left & right pixel by pixel. If any pixel value is less/greater than the near and far limits, that is marked as the tree edge. Once the tree edge indices for the color frame is calculated, these pixel indices are converted into vertex indices. For this conversion, it should be remembered that the point cloud is populated in the order of the image pixels (left to right, then down), and in the `float[]` vertex array the coordinates are arranged in the order `x` `y` `z` for all the vertices (For more information see [here](https://github.com/IntelRealSense/librealsense/issues/1783#issuecomment-392536795) and [here](https://github.com/IntelRealSense/librealsense/issues/9340#issuecomment-880045972)):

```
p(x,y) -> vertices[y * width + x], vertices[y * width + x + 1], vertices[y * width + x + 2] // x, y, z real-world coordinates  
```
Once the vertex coordinates of the tree edges are calculated, their `x` coordinates are acquired from the vertex array and the raw diameter is assumed to be the difference of these coordinates.

### Using PointCloud Only

**NOTE:** Even though this approach uses only the point cloud to calculate the diameter, it still computes the foreground image just for displaying on the UI. This is to provide a visual clue to the user for ensuring that the correct tree is measured and the measurement is acceptable.

In this approach, the tree edge lines are searched directly within the point cloud vertex array. The index of the central point of the camera is calculated (for the conversion of pixel indices to vertex index, the same method detailed in the above section (*Combining ColorFrame and PointCloud*) is used. Therefore, the mid-point vertex index is:

```
int midIndex = (centerY * depthFrame.getWidth() + centerX) * 3; // central vertex x coordinate
```

where `centerY` and `centerX` are the central coordinates of the `depthFrame`. 

Then from this central vertex, the search is executed backwards and forwards. In this search, the vertex that has a `z` value which is either `0`, greater than the far limit or less than the near limit is assumed to be outside of the tree, and the previous vertex is assigned as the tree edge. In the execution of this search:

* when going to the right (incrementing), `x` coordinate indices are `midIndex + (i * 3)` and `z` coordinate indices are `(midIndex + 2) + (i * 3)`. 
* when going to the left (decrementing), `x` coordinate indices are `midIndex - (i * 3` and `z` coordinate indices are `(midIndex - 1) - (i * 3)`.

Once the left and right vertex points just outside of the tree (with their `z` coordinate indices) are found, their corresponding `x` coordinate indices can be found as:

* for the left edge (decrementing side), `x` is just the right neighbor of the `z`, i.e. `z_index_left + 1`
* for the right edge (incrementing side), `x` belongs to the previous vertex of the `z`, i.e. `z_index_right - 5`

Once the `x` coordinates of the tree edge vertices are found, the raw diameter is assumed to be the difference of these coordinates.