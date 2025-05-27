package com.example.signlanguagerecognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.flex.FlexDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

class SignLanguageClassifier(private val context: Context) {
    
    private var interpreter: Interpreter? = null
    private var sequenceLength: Int = 30
    private var featureSize: Int = 226  // Fixed: matches training data exactly
    private var frameBuffer: ConcurrentLinkedQueue<FloatArray> = ConcurrentLinkedQueue()
    private var mediaPipeExtractor: MediaPipeFeatureExtractor? = null
    
    // Labels for sign language - you should replace these with your actual labels
    private val labels = listOf(
        // "hello", "i", "you", "yes", "no", "how", "help", "good", "thanks", "goodbye"
        "idle", "hello", "good"
    )
    
    init {
        setupInterpreter()
        setupMediaPipe()
    }
    
    private fun setupMediaPipe() {
        try {
            mediaPipeExtractor = MediaPipeFeatureExtractor(context)
            mediaPipeExtractor?.initialize()
            Log.d("SignLanguageClassifier", "MediaPipe feature extractor initialized")
        } catch (e: Exception) {
            Log.e("SignLanguageClassifier", "Failed to initialize MediaPipe: ${e.message}", e)
        }
    }
    
    private fun setupInterpreter() {
        try {
            // Check if model file exists
            val assetManager = context.assets
            val modelExists = try {
                assetManager.open("sign_language_model.tflite").use { true }
            } catch (e: Exception) {
                false
            }
            
            if (!modelExists) {
                Log.e("SignLanguageClassifier", "Model file 'sign_language_model.tflite' not found in assets folder")
                return
            }
            
            // Load your .tflite model from assets folder
            val modelFile = FileUtil.loadMappedFile(context, "sign_language_model.tflite")
            
            // Try multiple interpreter configurations
            interpreter = tryCreateInterpreter(modelFile)
            
            // Get input tensor dimensions
            val inputShape = interpreter?.getInputTensor(0)?.shape()
            inputShape?.let {
                // Expecting input shape: [1, 30, 226] for batch, sequence, features
                if (it.size >= 2) {
                    sequenceLength = it[it.size - 2] // Second to last dimension (30)
                    featureSize = it[it.size - 1] // Last dimension (226)
                    Log.d("SignLanguageClassifier", "Model loaded successfully. Input shape: [${it.joinToString(", ")}]")
                    Log.d("SignLanguageClassifier", "Interpreted as: sequenceLength=$sequenceLength, featureSize=$featureSize")
                    
                    // Verify feature size matches training expectation
                    if (featureSize != 226) {
                        Log.w("SignLanguageClassifier", "⚠️ Model expects $featureSize features, but training used 226. Check model conversion.")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("SignLanguageClassifier", "Error loading model: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    private fun tryCreateInterpreter(modelFile: java.nio.MappedByteBuffer): Interpreter? {
        // Try 1: Standard interpreter
        try {
            val options = Interpreter.Options().apply {
                setUseNNAPI(false) // Disable NNAPI initially
                setNumThreads(4)
            }
            val interpreter = Interpreter(modelFile, options)
            Log.d("SignLanguageClassifier", "✅ Standard interpreter created successfully")
            return interpreter
        } catch (e: Exception) {
            Log.w("SignLanguageClassifier", "❌ Standard interpreter failed: ${e.message}")
        }
        
        // Try 2: Flex delegate for TensorFlow ops
        try {
            val flexDelegate = FlexDelegate()
            val options = Interpreter.Options().apply {
                addDelegate(flexDelegate)
                setUseNNAPI(false)
                setNumThreads(4)
            }
            val interpreter = Interpreter(modelFile, options)
            Log.d("SignLanguageClassifier", "✅ Flex delegate interpreter created successfully")
            return interpreter
        } catch (e: Exception) {
            Log.w("SignLanguageClassifier", "❌ Flex delegate interpreter failed: ${e.message}")
        }
        
        // Try 3: CPU only, no delegates
        try {
            val options = Interpreter.Options().apply {
                setUseNNAPI(false)
                setUseXNNPACK(false)
                setNumThreads(1)
            }
            val interpreter = Interpreter(modelFile, options)
            Log.d("SignLanguageClassifier", "✅ CPU-only interpreter created successfully")
            return interpreter
        } catch (e: Exception) {
            Log.e("SignLanguageClassifier", "❌ All interpreter creation attempts failed: ${e.message}")
        }
        
        return null
    }
    
    fun classify(image: ImageProxy): String {
        return try {
            if (interpreter == null) {
                return "❌ Model not loaded. Please add 'sign_language_model.tflite' to assets folder"
            }
            
            val features = extractFeaturesFromImage(image)
            addFeaturesToBuffer(features)
            
            if (frameBuffer.size >= sequenceLength) {
                classifySequence()
            } else {
                "Collecting frames... (${frameBuffer.size}/$sequenceLength)"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "Error processing image: ${e.message}"
        }
    }
    
    private fun extractFeaturesFromImage(image: ImageProxy): FloatArray {
        // Convert ImageProxy to feature vector of size 226 (matching training)
        // Uses MediaPipe Holistic detection to extract pose + hand landmarks
        val bitmap = imageProxyToBitmap(image)
        return extractFeaturesFromBitmap(bitmap)
    }
    
    private fun extractFeaturesFromBitmap(bitmap: Bitmap): FloatArray {
        // Use MediaPipe for feature extraction (matches your training data exactly)
        val features = mediaPipeExtractor?.extractFeatures(bitmap) ?: run {
            Log.w("SignLanguageClassifier", "MediaPipe extractor not available, using fallback")
            createFallbackFeatures()
        }
        
        // DEBUG: Log feature extraction source
        Log.d("SignLanguageClassifier", "Feature extraction: ${if (mediaPipeExtractor?.isReady() == true) "MediaPipe" else "Fallback"}")
        Log.d("SignLanguageClassifier", "Feature size: ${features.size}, expected: $featureSize")
        Log.d("SignLanguageClassifier", "Feature range: ${features.minOrNull()} to ${features.maxOrNull()}")
        Log.d("SignLanguageClassifier", "First 10 features: ${features.take(10)}")
        
        return features
    }
    
    private fun createFallbackFeatures(): FloatArray {
        Log.w("SignLanguageClassifier", "Using fallback feature extraction")
        val features = FloatArray(featureSize)
        
        // Create realistic fallback features matching training structure:
        // Pose (100) + Left Hand (63) + Right Hand (63) = 226 features
        val random = kotlin.random.Random.Default
        var index = 0
        
        // Pose landmarks (100 features: 25 landmarks × 4 values each)
        repeat(25) {
            features[index++] = 0.5f + random.nextFloat() * 0.2f - 0.1f // x
            features[index++] = 0.5f + random.nextFloat() * 0.4f - 0.2f // y  
            features[index++] = random.nextFloat() * 0.1f - 0.05f        // z
            features[index++] = 0.8f + random.nextFloat() * 0.2f         // visibility
        }
        
        // Left hand landmarks (63 features: 21 landmarks × 3 values each)
        repeat(21) {
            features[index++] = 0.4f + random.nextFloat() * 0.2f - 0.1f // x (left side)
            features[index++] = 0.5f + random.nextFloat() * 0.2f - 0.1f // y
            features[index++] = random.nextFloat() * 0.1f - 0.05f        // z
        }
        
        // Right hand landmarks (63 features: 21 landmarks × 3 values each)  
        repeat(21) {
            features[index++] = 0.6f + random.nextFloat() * 0.2f - 0.1f // x (right side)
            features[index++] = 0.5f + random.nextFloat() * 0.2f - 0.1f // y
            features[index++] = random.nextFloat() * 0.1f - 0.05f        // z
        }
        
        // DEBUG: Log fallback feature details
        Log.d("SignLanguageClassifier", "Generated fallback features - size: ${features.size}")
        Log.d("SignLanguageClassifier", "Fallback feature range: ${features.minOrNull()} to ${features.maxOrNull()}")
        Log.d("SignLanguageClassifier", "Fallback first 10: ${features.take(10)}")
        
        return features
    }
    
    private fun addFeaturesToBuffer(features: FloatArray) {
        frameBuffer.offer(features)
        
        // Keep only the latest 30 feature vectors
        while (frameBuffer.size > sequenceLength) {
            frameBuffer.poll()
        }
    }
    
    private fun classifySequence(): String {
        return try {
            interpreter?.let { interpreter ->
                val features = frameBuffer.toList()
                if (features.size < sequenceLength) {
                    return "Not enough frames"
                }
                
                // Take the latest feature vectors up to sequence length
                val sequence = features.takeLast(sequenceLength)
                
                // Convert sequence to input tensor with correct shape
                val inputBuffer = convertSequenceToBuffer(sequence)
                
                // Prepare output
                val outputShape = interpreter.getOutputTensor(0).shape()
                val outputSize = if (outputShape.size > 1) outputShape[1] else outputShape[0]
                
                Log.d("SignLanguageClassifier", "Output shape: [${outputShape.joinToString(", ")}], output size: $outputSize")
                
                // Create output array that matches the expected output shape
                val outputBuffer = when (outputShape.size) {
                    1 -> FloatArray(outputSize)
                    2 -> Array(1) { FloatArray(outputSize) }
                    else -> Array(1) { FloatArray(outputSize) }
                }
                
                // Run inference
                interpreter.run(inputBuffer, outputBuffer)
                
                // Extract predictions based on output format
                val predictions = when (outputBuffer) {
                    is FloatArray -> outputBuffer
                    is Array<*> -> (outputBuffer[0] as FloatArray)
                    else -> {
                        Log.e("SignLanguageClassifier", "Unexpected output buffer type")
                        return "❌ Output format error"
                    }
                }
                
                // DEBUG: Log all prediction values to diagnose the issue
                Log.d("SignLanguageClassifier", "=== PREDICTION DEBUG ===")
                Log.d("SignLanguageClassifier", "Raw predictions: ${predictions.contentToString()}")
                Log.d("SignLanguageClassifier", "Prediction details:")
                for (i in predictions.indices) {
                    if (i < labels.size) {
                        Log.d("SignLanguageClassifier", "  [$i] ${labels[i]}: ${predictions[i]} (${String.format("%.2f", predictions[i] * 100)}%)")
                    } else {
                        Log.d("SignLanguageClassifier", "  [$i] UNKNOWN_LABEL: ${predictions[i]}")
                    }
                }
                
                val maxIndex = predictions.indices.maxByOrNull { predictions[it] } ?: -1
                Log.d("SignLanguageClassifier", "Max prediction index: $maxIndex")
                
                if (maxIndex >= 0 && maxIndex < labels.size) {
                    val confidence = predictions[maxIndex]
                    val result = "✅ ${labels[maxIndex]} (${String.format("%.2f", confidence * 100)}%)"
                    Log.d("SignLanguageClassifier", "Final prediction: $result")
                    result
                } else {
                    val result = "❓ Unknown prediction (index: $maxIndex, predictions: ${predictions.size}, labels: ${labels.size})"
                    Log.e("SignLanguageClassifier", result)
                    result
                }
            } ?: "❌ Model not loaded - add .tflite file to assets folder"
        } catch (e: Exception) {
            Log.e("SignLanguageClassifier", "Classification error: ${e.message}", e)
            e.printStackTrace()
            "❌ Classification error: ${e.message}"
        }
    }
    
    private fun convertSequenceToBuffer(sequence: List<FloatArray>): Any {
        interpreter?.let { interpreter ->
            val inputShape = interpreter.getInputTensor(0).shape()
            Log.d("SignLanguageClassifier", "Converting sequence for input shape: [${inputShape.joinToString(", ")}]")
            
            return when (inputShape.size) {
                2 -> {
                    // Shape: [30, 1662] - Direct 2D array
                    Log.d("SignLanguageClassifier", "Using 2D array format")
                    val inputArray = Array(sequenceLength) { FloatArray(featureSize) }
                    
                    for (frameIndex in 0 until sequenceLength) {
                        val features = if (frameIndex < sequence.size) {
                            sequence[frameIndex]
                        } else {
                            sequence.lastOrNull() ?: FloatArray(featureSize)
                        }
                        
                        for (i in 0 until featureSize) {
                            inputArray[frameIndex][i] = if (i < features.size) features[i] else 0f
                        }
                    }
                    inputArray
                }
                
                3 -> {
                    // Shape: [1, 30, 1662] - 3D array with batch dimension
                    Log.d("SignLanguageClassifier", "Using 3D array format with batch dimension")
                    val inputArray = Array(1) { Array(sequenceLength) { FloatArray(featureSize) } }
                    
                    for (frameIndex in 0 until sequenceLength) {
                        val features = if (frameIndex < sequence.size) {
                            sequence[frameIndex]
                        } else {
                            sequence.lastOrNull() ?: FloatArray(featureSize)
                        }
                        
                        for (i in 0 until featureSize) {
                            inputArray[0][frameIndex][i] = if (i < features.size) features[i] else 0f
                        }
                    }
                    inputArray
                }
                
                4 -> {
                    // Shape: [1, 30, 1662, 1] - 4D array (batch, sequence, features, channel)
                    Log.d("SignLanguageClassifier", "Using 4D array format")
                    val inputArray = Array(1) { Array(sequenceLength) { Array(featureSize) { FloatArray(1) } } }
                    
                    for (frameIndex in 0 until sequenceLength) {
                        val features = if (frameIndex < sequence.size) {
                            sequence[frameIndex]
                        } else {
                            sequence.lastOrNull() ?: FloatArray(featureSize)
                        }
                        
                        for (i in 0 until featureSize) {
                            inputArray[0][frameIndex][i][0] = if (i < features.size) features[i] else 0f
                        }
                    }
                    inputArray
                }
                
                else -> {
                    Log.e("SignLanguageClassifier", "Unsupported input shape: [${inputShape.joinToString(", ")}]")
                    // Fallback to 2D array
                    Array(sequenceLength) { FloatArray(featureSize) }
                }
            }
        }
        
        // Fallback if interpreter is null
        return Array(sequenceLength) { FloatArray(featureSize) }
    }
    
    // Method to process a complete video sequence (for recorded videos)
    fun classifyVideoSequence(bitmaps: List<Bitmap>): String {
        return try {
            if (bitmaps.size < sequenceLength) {
                return "Video too short: ${bitmaps.size} frames, need $sequenceLength"
            }
            
            Log.d("SignLanguageClassifier", "Processing video with ${bitmaps.size} frames")
            
            // Take evenly spaced frames if we have more than needed
            val selectedFrames = if (bitmaps.size == sequenceLength) {
                bitmaps
            } else {
                selectFramesFromVideo(bitmaps, sequenceLength)
            }
            
            Log.d("SignLanguageClassifier", "Selected ${selectedFrames.size} frames for processing")
            
            // Extract features from all frames
            val featureSequence = selectedFrames.map { bitmap -> 
                extractFeaturesFromBitmap(bitmap) 
            }
            
            Log.d("SignLanguageClassifier", "Extracted features for ${featureSequence.size} frames")
            
            // Run inference
            interpreter?.let { interpreter ->
                val inputBuffer = convertSequenceToBuffer(featureSequence)
                
                val outputShape = interpreter.getOutputTensor(0).shape()
                val outputSize = if (outputShape.size > 1) outputShape[1] else outputShape[0]
                
                Log.d("SignLanguageClassifier", "Video classification - Output shape: [${outputShape.joinToString(", ")}]")
                
                // Create output array that matches the expected output shape
                val outputBuffer = when (outputShape.size) {
                    1 -> FloatArray(outputSize)
                    2 -> Array(1) { FloatArray(outputSize) }
                    else -> Array(1) { FloatArray(outputSize) }
                }
                
                interpreter.run(inputBuffer, outputBuffer)
                
                // Extract predictions based on output format
                val predictions = when (outputBuffer) {
                    is FloatArray -> outputBuffer
                    is Array<*> -> (outputBuffer[0] as FloatArray)
                    else -> {
                        Log.e("SignLanguageClassifier", "Unexpected output buffer type in video classification")
                        return "❌ Video output format error"
                    }
                }
                
                val maxIndex = predictions.indices.maxByOrNull { predictions[it] } ?: -1
                
                if (maxIndex >= 0 && maxIndex < labels.size) {
                    val confidence = predictions[maxIndex]
                    "✅ Video: ${labels[maxIndex]} (${String.format("%.2f", confidence * 100)}%)"
                } else {
                    "❓ Video: Unknown prediction (index: $maxIndex)"
                }
            } ?: "❌ Model not loaded"
            
        } catch (e: Exception) {
            Log.e("SignLanguageClassifier", "Video classification error: ${e.message}", e)
            e.printStackTrace()
            "❌ Video classification error: ${e.message}"
        }
    }
    
    private fun selectFramesFromVideo(frames: List<Bitmap>, targetCount: Int): List<Bitmap> {
        if (frames.size <= targetCount) return frames
        
        val step = frames.size.toFloat() / targetCount
        val selectedFrames = mutableListOf<Bitmap>()
        
        for (i in 0 until targetCount) {
            val index = (i * step).toInt()
            selectedFrames.add(frames[index])
        }
        
        return selectedFrames
    }
    
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
    
    fun clearFrameBuffer() {
        frameBuffer.clear()
    }
    
    fun getFrameCount(): Int {
        return frameBuffer.size
    }
    
    fun close() {
        interpreter?.close()
        mediaPipeExtractor?.release()
        frameBuffer.clear()
    }
}
