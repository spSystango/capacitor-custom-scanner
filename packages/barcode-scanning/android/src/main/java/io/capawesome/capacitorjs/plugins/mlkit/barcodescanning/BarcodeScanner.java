/**
 * Copyright (c) 2023 Robin Genz
 */
package io.capawesome.capacitorjs.plugins.mlkit.barcodescanning;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Point;
import android.media.Image;
import android.net.Uri;
import android.provider.Settings;
import android.view.Display;
import android.view.WindowManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import com.getcapacitor.PermissionState;
import com.getcapacitor.PluginCall;
import com.google.android.gms.common.moduleinstall.InstallStatusListener;
import com.google.android.gms.common.moduleinstall.ModuleInstall;
import com.google.android.gms.common.moduleinstall.ModuleInstallClient;
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest;
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
import com.google.mlkit.vision.common.InputImage;
import io.capawesome.capacitorjs.plugins.mlkit.barcodescanning.classes.options.SetZoomRatioOptions;
import io.capawesome.capacitorjs.plugins.mlkit.barcodescanning.classes.results.GetMaxZoomRatioResult;
import io.capawesome.capacitorjs.plugins.mlkit.barcodescanning.classes.results.GetMinZoomRatioResult;
import io.capawesome.capacitorjs.plugins.mlkit.barcodescanning.classes.results.GetZoomRatioResult;

// Modified SDK: Import for custom changes
import android.graphics.ImageFormat;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.util.Base64;
import android.util.Log;

public class BarcodeScanner implements ImageAnalysis.Analyzer {

    @NonNull
    private final BarcodeScannerPlugin plugin;

    private final Point displaySize;

    @Nullable
    private com.google.mlkit.vision.barcode.BarcodeScanner barcodeScannerInstance;

    @Nullable
    private Camera camera;

    @Nullable
    private ProcessCameraProvider processCameraProvider;

    @Nullable
    private PreviewView previewView;

    @Nullable
    private ScanSettings scanSettings;

    @Nullable
    private ModuleInstallProgressListener moduleInstallProgressListener;

    private boolean isTorchEnabled = false;

    public BarcodeScanner(BarcodeScannerPlugin plugin) {
        this.plugin = plugin;
        this.displaySize = this.getDisplaySize();
    }

    /**
     * Must run on UI thread.
     */
    public void startScan(ScanSettings scanSettings, StartScanResultCallback callback) {
        // Stop the camera if running
        stopScan();
        // Hide WebView background
        hideWebViewBackground();

        this.scanSettings = scanSettings;

        BarcodeScannerOptions options = buildBarcodeScannerOptions(scanSettings);
        barcodeScannerInstance = BarcodeScanning.getClient(options);

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(plugin.getContext()), this);

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(plugin.getContext());
        cameraProviderFuture.addListener(
            () -> {
                try {
                    processCameraProvider = cameraProviderFuture.get();

                    CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(this.scanSettings.lensFacing).build();

                    previewView = plugin.getActivity().findViewById(R.id.preview_view);
                    previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);

                    Preview preview = new Preview.Builder().build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());

                    // Start the camera
                    camera =
                        processCameraProvider.bindToLifecycle((LifecycleOwner) plugin.getContext(), cameraSelector, preview, imageAnalysis);

                    callback.success();
                } catch (Exception exception) {
                    callback.error(exception);
                }
            },
            ContextCompat.getMainExecutor(plugin.getContext())
        );
    }

    /**
     * Must run on UI thread.
     */
    public void stopScan() {
        showWebViewBackground();
        disableTorch();
        // Stop the camera
        if (processCameraProvider != null) {
            processCameraProvider.unbindAll();
        }
        processCameraProvider = null;
        camera = null;
        barcodeScannerInstance = null;
        scanSettings = null;
    }

    public void readBarcodesFromImage(String path, ScanSettings scanSettings, ReadBarcodesFromImageResultCallback callback)
        throws Exception {
        InputImage inputImage;
        try {
            inputImage = InputImage.fromFilePath(plugin.getContext(), Uri.parse(path));
        } catch (Exception exception) {
            throw new Exception(BarcodeScannerPlugin.ERROR_LOAD_IMAGE_FAILED);
        }

        BarcodeScannerOptions options = buildBarcodeScannerOptions(scanSettings);
        com.google.mlkit.vision.barcode.BarcodeScanner barcodeScannerInstance = BarcodeScanning.getClient(options);
        barcodeScannerInstance
            .process(inputImage)
            .addOnSuccessListener(
                barcodes -> {
                    callback.success(barcodes);
                }
            )
            .addOnFailureListener(
                exception -> {
                    callback.error(exception);
                }
            );
    }

    public void scan(ScanSettings scanSettings, ScanResultCallback callback) {
        GmsBarcodeScannerOptions options = buildGmsBarcodeScannerOptions(scanSettings);
        GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(plugin.getContext(), options);

        scanner
            .startScan()
            .addOnSuccessListener(
                barcode -> {
                    callback.success(barcode);
                }
            )
            .addOnCanceledListener(
                () -> {
                    callback.cancel();
                }
            )
            .addOnFailureListener(
                exception -> {
                    callback.error(exception);
                }
            );
    }

    public void isGoogleBarcodeScannerModuleAvailable(IsGoogleBarodeScannerModuleAvailableResultCallback callback) {
        GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(plugin.getContext());
        ModuleInstallClient moduleInstallClient = ModuleInstall.getClient(plugin.getContext());
        moduleInstallClient
            .areModulesAvailable(scanner)
            .addOnSuccessListener(
                response -> {
                    boolean isAvailable = response.areModulesAvailable();
                    callback.success(isAvailable);
                }
            )
            .addOnFailureListener(
                exception -> {
                    callback.error(exception);
                }
            );
    }

    public void installGoogleBarcodeScannerModule(InstallGoogleBarcodeScannerModuleResultCallback callback) {
        GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(plugin.getContext());
        InstallStatusListener listener = new ModuleInstallProgressListener(this);
        ModuleInstallRequest moduleInstallRequest = ModuleInstallRequest.newBuilder().addApi(scanner).setListener(listener).build();
        ModuleInstallClient moduleInstallClient = ModuleInstall.getClient(plugin.getContext());
        moduleInstallClient
            .installModules(moduleInstallRequest)
            .addOnSuccessListener(
                moduleInstallResponse -> {
                    if (moduleInstallResponse.areModulesAlreadyInstalled()) {
                        callback.error(new Exception(BarcodeScannerPlugin.ERROR_GOOGLE_BARCODE_SCANNER_MODULE_ALREADY_INSTALLED));
                    } else {
                        callback.success();
                    }
                }
            )
            .addOnFailureListener(
                exception -> {
                    callback.error(exception);
                }
            );
    }

    public boolean isSupported() {
        return plugin.getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    public void enableTorch() {
        if (camera == null) {
            return;
        }
        camera.getCameraControl().enableTorch(true);
        isTorchEnabled = true;
    }

    public void disableTorch() {
        if (camera == null) {
            return;
        }
        camera.getCameraControl().enableTorch(false);
        isTorchEnabled = false;
    }

    public void toggleTorch() {
        if (isTorchEnabled) {
            disableTorch();
        } else {
            enableTorch();
        }
    }

    public boolean isTorchEnabled() {
        return isTorchEnabled;
    }

    public boolean isTorchAvailable() {
        return plugin.getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
    }

    public void setZoomRatio(SetZoomRatioOptions options) {
        float zoomRatio = options.getZoomRatio();
        if (camera == null) {
            return;
        }
        camera.getCameraControl().setZoomRatio(zoomRatio);
    }

    @Nullable
    public GetZoomRatioResult getZoomRatio() {
        if (camera == null) {
            return null;
        }
        float zoomRatio = camera.getCameraInfo().getZoomState().getValue().getZoomRatio();
        return new GetZoomRatioResult(zoomRatio);
    }

    @Nullable
    public GetMinZoomRatioResult getMinZoomRatio() {
        if (camera == null) {
            return null;
        }
        float minZoomRatio = camera.getCameraInfo().getZoomState().getValue().getMinZoomRatio();
        return new GetMinZoomRatioResult(minZoomRatio);
    }

    @Nullable
    public GetMaxZoomRatioResult getMaxZoomRatio() {
        if (camera == null) {
            return null;
        }
        float maxZoomRatio = camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio();
        return new GetMaxZoomRatioResult(maxZoomRatio);
    }

    public void openSettings(PluginCall call) {
        Uri uri = Uri.fromParts("package", plugin.getAppId(), null);
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri);
        plugin.startActivityForResult(call, intent, "openSettingsResult");
    }

    public PermissionState getCameraPermission() {
        return plugin.getPermissionState(BarcodeScannerPlugin.CAMERA);
    }

    public void requestCameraPermission(PluginCall call) {
        plugin.requestPermissionForAlias(BarcodeScannerPlugin.CAMERA, call, "cameraPermissionsCallback");
    }

    public boolean requestCameraPermissionIfNotDetermined(PluginCall call) throws Exception {
        PermissionState state = getCameraPermission();
        if (state == PermissionState.GRANTED) {
            return true;
        } else if (state == PermissionState.DENIED) {
            throw new Exception(BarcodeScannerPlugin.ERROR_PERMISSION_DENIED);
        } else {
            requestCameraPermission(call);
            return false;
        }
    }

    public boolean isCameraActive() {
        return camera != null;
    }

    @Override
    public void analyze(@NonNull ImageProxy imageProxy) {
        @SuppressLint("UnsafeOptInUsageError")
        Image image = imageProxy.getImage();

        if (image == null || barcodeScannerInstance == null) {
            return;
        }

        InputImage inputImage = InputImage.fromMediaImage(image, imageProxy.getImageInfo().getRotationDegrees());
        Point imageSize = new Point(inputImage.getWidth(), inputImage.getHeight());
        barcodeScannerInstance
            .process(inputImage)
            .addOnSuccessListener(
                barcodes -> {
                    if (scanSettings == null) {
                        // Scanning stopped while processing the image
                        return;
                    }
                    for (Barcode barcode : barcodes) {
                        // Modified SDK: Hold the processed image and crop the QR area
                        Rect qrBoundingBox  = barcode.getBoundingBox();
                        String scannedImage = ""; // Full scanned image, can process directly 
                        String cropedQrImage = getQrImage(image, qrBoundingBox);
                        
                        handleScannedBarcode(barcode, imageSize, scannedImage, cropedQrImage);
                    }
                }
            )
            .addOnFailureListener(
                exception -> {
                    handleScanError(exception);
                }
            )
            .addOnCompleteListener(
                task -> {
                    imageProxy.close();
                    image.close();
                }
            );
    }

    // Modified SDK: Process the Yuv image and return it to base64 String
    public String getQrImage(Image image, Rect qrBoundingBox){
        String base64String = null;
        if(image.getFormat() == ImageFormat.YUV_420_888){
            base64String = convertYuvToBase64(image, qrBoundingBox);
        }else{
            base64String = "";
            image.close();
        }
        return base64String;
    }

    // Modified SDK: Convert YUV_420_888 to YUV image then crop and then convert it to the base64  
    private String convertYuvToBase64(Image image, Rect qrBoundingBox) {
        YuvImage yuvImage = imageToYuvImage(image);
        if (yuvImage == null) {
            Log.e("ImageConverter", "Failed to convert YUV to JPEG.");
            return "";
        }

        // Convert YUV image to JPEG format and then encode it
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, image.getWidth(), image.getHeight()), 100, outputStream);
        byte[] jpegBytes = outputStream.toByteArray();

        byte[] rotatedByte = rotateAndCropQRImage(jpegBytes, 90, qrBoundingBox);

        // Convert to Base64
        return Base64.encodeToString(rotatedByte, Base64.NO_WRAP);
    }

    // Modified SDK: Method to convert Image (YUV_420_888) to YuvImage
    private YuvImage imageToYuvImage(Image image) {
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();  // Y
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();  // U
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();  // V

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        // U and V are swapped
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        // Convert YUV_420_888 to YuvImage (compatible with JPEG compression)
        return new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
    }

    // Modified SDK: Rotate and crop the QR code image
    public static byte[] rotateAndCropQRImage(byte[] jpegBytes, float rotationAngle, Rect qrBoundingBox) {
        // Step 1: Convert byte[] (JPEG) to Bitmap
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);

        // Step 2: Rotate the Bitmap
        Bitmap rotatedBitmap = rotateImage(bitmap, rotationAngle);

        // Step 3: Crop the QR code using bounding box
        // Ensure the bounding box coordinates are within the rotated bitmap's dimensions
        int cropX = Math.max(0, qrBoundingBox.left);
        int cropY = Math.max(0, qrBoundingBox.top);
        int cropWidth = Math.min(rotatedBitmap.getWidth() - cropX, qrBoundingBox.width());
        int cropHeight = Math.min(rotatedBitmap.getHeight() - cropY, qrBoundingBox.height());

        // Crop the QR code from the rotated bitmap
        Bitmap croppedBitmap = Bitmap.createBitmap(rotatedBitmap, cropX, cropY, cropWidth, cropHeight);


        // Step 4: Convert the rotated Bitmap back to byte[] (JPEG)
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
        return outputStream.toByteArray();
    }

    // Modified SDK: Rotate the Bitmap image to 90 Degree
    private static Bitmap rotateImage(Bitmap source, float angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    // Custom Method END

    public void handleGoogleBarcodeScannerModuleInstallProgress(
        @ModuleInstallStatusUpdate.InstallState int state,
        @Nullable Integer progress
    ) {
        plugin.notifyGoogleBarcodeScannerModuleInstallProgressListener(state, progress);
        boolean isTerminateState = ModuleInstallProgressListener.isTerminateState(state);
        if (isTerminateState && moduleInstallProgressListener != null) {
            ModuleInstallClient moduleInstallClient = ModuleInstall.getClient(plugin.getContext());
            moduleInstallClient.unregisterListener(moduleInstallProgressListener);
            moduleInstallProgressListener = null;
        }
    }

    private Point getDisplaySize() {
        WindowManager wm = (WindowManager) plugin.getContext().getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        return size;
    }

    /**
     * Must run on UI thread.
     */
    private void hideWebViewBackground() {
        plugin.getBridge().getWebView().setBackgroundColor(Color.TRANSPARENT);
    }

    /**
     * Must run on UI thread.
     */
    private void showWebViewBackground() {
        plugin.getBridge().getWebView().setBackgroundColor(Color.WHITE);
    }

    private void handleScannedBarcode(Barcode barcode, Point imageSize, String scannedImage, String qrImage) {
        plugin.notifyBarcodeScannedListener(barcode, imageSize, scannedImage, qrImage);
    }

    private void handleScanError(Exception exception) {
        plugin.notifyScanErrorListener(exception.getMessage());
    }

    private BarcodeScannerOptions buildBarcodeScannerOptions(ScanSettings scanSettings) {
        int[] formats = scanSettings.formats.length == 0 ? new int[] { Barcode.FORMAT_ALL_FORMATS } : scanSettings.formats;
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder().setBarcodeFormats(formats[0], formats).build();
        return options;
    }

    private GmsBarcodeScannerOptions buildGmsBarcodeScannerOptions(ScanSettings scanSettings) {
        int[] formats = scanSettings.formats.length == 0 ? new int[] { Barcode.FORMAT_ALL_FORMATS } : scanSettings.formats;
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder().setBarcodeFormats(formats[0], formats).build();
        return options;
    }
}
