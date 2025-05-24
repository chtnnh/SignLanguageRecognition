package com.example.signlanguagerecognition

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.lifecycleScope
import com.example.signlanguagerecognition.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "SignLanguageRecognition"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
    
    private lateinit var viewBinding: ActivityMainBinding
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var signLanguageClassifier: SignLanguageClassifier
    private lateinit var videoProcessor: VideoProcessor
    private var isRealTimePredictionEnabled = false
    private var frameProcessingInterval = 3 // Process every 3rd frame to reduce load
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        
        // Initialize TensorFlow Lite model and video processor
        signLanguageClassifier = SignLanguageClassifier(this)
        videoProcessor = VideoProcessor(this)
        
        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }
        
        // Set up the listeners for video recording and prediction buttons
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }
        viewBinding.predictButton.setOnClickListener { 
            toggleRealTimePrediction()
        }
        viewBinding.clearButton.setOnClickListener {
            clearFrameBuffer()
        }
        viewBinding.selectVideoButton.setOnClickListener {
            selectVideoFile()
        }
        
        cameraExecutor = Executors.newSingleThreadExecutor()
    }
    
    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return
        
        viewBinding.videoCaptureButton.isEnabled = false
        
        val curRecording = recording
        if (curRecording != null) {
            // Stop the current recording session
            curRecording.stop()
            recording = null
            return
        }
        
        // Create new recording session
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SignLanguage")
            }
        }
        
        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()
        
        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (PermissionChecker.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PermissionChecker.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.stop_capture)
                            isEnabled = true
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                            Log.d(TAG, msg)
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: ${recordEvent.error}")
                        }
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.start_capture)
                            isEnabled = true
                        }
                    }
                }
            }
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
            }
            
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.HIGHEST,
                        FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                    )
                )
                .build()
            
            videoCapture = VideoCapture.withOutput(recorder)
            
            // Image analysis for real-time prediction
            val imageAnalyzer = ImageAnalysis.Builder().build().also {
                it.setAnalyzer(cameraExecutor, { image ->
                    analyzeImage(image)
                })
            }
            
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
            
        }, ContextCompat.getMainExecutor(this))
    }
    
    private var frameCounter = 0
    
    private fun analyzeImage(image: ImageProxy) {
        // Only process frames when real-time prediction is enabled
        if (!isRealTimePredictionEnabled) {
            image.close()
            return
        }
        
        // Process every nth frame to reduce computational load
        frameCounter++
        if (frameCounter % frameProcessingInterval != 0) {
            image.close()
            return
        }
        
        // Convert ImageProxy to bitmap and run inference
        val result = signLanguageClassifier.classify(image)
        
        runOnUiThread {
            viewBinding.predictionText.text = result
            viewBinding.frameCountText.text = "Frames collected: ${signLanguageClassifier.getFrameCount()}/30"
        }
        
        image.close()
    }
    
    private fun toggleRealTimePrediction() {
        isRealTimePredictionEnabled = !isRealTimePredictionEnabled
        
        if (isRealTimePredictionEnabled) {
            viewBinding.predictButton.text = "Stop Prediction"
            viewBinding.predictButton.backgroundTintList = 
                ContextCompat.getColorStateList(this, R.color.design_default_color_error)
            signLanguageClassifier.clearFrameBuffer()
            Toast.makeText(this, "Real-time prediction started - collecting 30 frames", Toast.LENGTH_SHORT).show()
        } else {
            viewBinding.predictButton.text = "Start Prediction"
            viewBinding.predictButton.backgroundTintList = 
                ContextCompat.getColorStateList(this, R.color.design_default_color_primary)
            Toast.makeText(this, "Real-time prediction stopped", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun clearFrameBuffer() {
        signLanguageClassifier.clearFrameBuffer()
        runOnUiThread {
            viewBinding.predictionText.text = "Frame buffer cleared"
            viewBinding.frameCountText.text = "Frames collected: 0/30"
        }
        Toast.makeText(this, "Frame buffer cleared", Toast.LENGTH_SHORT).show()
    }
    
    private fun selectVideoFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "video/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        videoPickerLauncher.launch(intent)
    }
    
    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { videoUri ->
                processSelectedVideo(videoUri)
            }
        }
    }
    
    private fun processSelectedVideo(videoUri: Uri) {
        viewBinding.predictionText.text = "Processing video..."
        viewBinding.frameCountText.text = "Extracting 30 frames from video..."
        
        lifecycleScope.launch {
            try {
                val result = videoProcessor.processVideoFile(videoUri, signLanguageClassifier)
                
                runOnUiThread {
                    viewBinding.predictionText.text = "Video Result: $result"
                    viewBinding.frameCountText.text = "Video processing completed"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    viewBinding.predictionText.text = "Error processing video: ${e.message}"
                    viewBinding.frameCountText.text = "Video processing failed"
                }
            }
        }
    }
    
    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }
    
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
    
    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && !it.value)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(
                    baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                startCamera()
            }
        }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        signLanguageClassifier.close()
    }
}
