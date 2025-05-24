package com.example.signlanguagerecognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
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
    private var featureSize: Int = 1662
    private var frameBuffer: ConcurrentLinkedQueue<FloatArray> = ConcurrentLinkedQueue()
    
    // Labels for sign language - you should replace these with your actual labels
    private val labels = listOf(
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
        "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
        "del", "nothing", "space"
    )
    
    init {
        setupInterpreter()
    }
    
    private fun setupInterpreter() {
        try {
            // Load your .tflite model from assets folder
            // Make sure to place your model file in app/src/main/assets/
            val modelFile = FileUtil.loadMappedFile(context, "sign_language_model.tflite")
            
            val options = Interpreter.Options().apply {
                // Use GPU if available
                setUseNNAPI(true)
                setNumThreads(4)
            }
            
            interpreter = Interpreter(modelFile, options)
            
            // Get input tensor dimensions
            val inputShape = interpreter?.getInputTensor(0)?.shape()
            inputShape?.let {
                // Expecting input shape: [30, 1662]
                sequenceLength = it[0] // Number of frames (30)
                featureSize = it[1] // Feature vector size (1662)
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun classify(image: ImageProxy): String {
        return try {
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
        // Convert ImageProxy to feature vector of size 1662
        // This is a placeholder - you need to implement the actual feature extraction
        // that matches your model's preprocessing
        val bitmap = imageProxyToBitmap(image)
        return extractFeaturesFromBitmap(bitmap)
    }
    
    private fun extractFeaturesFromBitmap(bitmap: Bitmap): FloatArray {
        // Placeholder feature extraction - replace with your actual preprocessing
        // This could be:
        // 1. Hand/body keypoint detection (MediaPipe, OpenPose)
        // 2. CNN feature extraction
        // 3. Custom feature engineering
        // 4. Pose estimation coordinates
        
        // For now, creating a dummy feature vector
        // TODO: Replace with your actual feature extraction logic
        val features = FloatArray(featureSize)
        
        // Example: Simple pixel-based features (replace with your method)
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 42, 42, true) // sqrt(1662) ≈ 41
        val pixels = IntArray(42 * 42)
        resizedBitmap.getPixels(pixels, 0, 42, 0, 0, 42, 42)
        
        for (i in 0 until minOf(pixels.size, featureSize)) {
            val pixel = pixels[i]
            // Convert to grayscale and normalize
            val gray = (0.299 * ((pixel shr 16) and 0xFF) + 
                       0.587 * ((pixel shr 8) and 0xFF) + 
                       0.114 * (pixel and 0xFF)) / 255.0
            features[i] = gray.toFloat()
        }
        
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
                
                // Take the latest 30 feature vectors
                val sequence = features.takeLast(sequenceLength)
                
                // Convert sequence to input tensor [30, 1662]
                val inputBuffer = convertSequenceToBuffer(sequence)
                
                // Prepare output
                val outputShape = interpreter.getOutputTensor(0).shape()
                val outputBuffer = Array(1) { FloatArray(outputShape[1]) }
                
                // Run inference
                interpreter.run(inputBuffer, outputBuffer)
                
                // Get prediction
                val predictions = outputBuffer[0]
                val maxIndex = predictions.indices.maxByOrNull { predictions[it] } ?: -1
                
                if (maxIndex >= 0 && maxIndex < labels.size) {
                    val confidence = predictions[maxIndex]
                    "${labels[maxIndex]} (${String.format("%.2f", confidence * 100)}%)"
                } else {
                    "Unknown"
                }
            } ?: "Model not loaded"
        } catch (e: Exception) {
            e.printStackTrace()
            "Classification error: ${e.message}"
        }
    }
    
    private fun convertSequenceToBuffer(sequence: List<FloatArray>): Array<FloatArray> {
        // Input shape: [30, 1662]
        val inputArray = Array(sequenceLength) { FloatArray(featureSize) }
        
        for (frameIndex in 0 until sequenceLength) {
            val features = if (frameIndex < sequence.size) {
                sequence[frameIndex]
            } else {
                // If we don't have enough frames, repeat the last frame
                sequence.lastOrNull() ?: FloatArray(featureSize)
            }
            
            // Copy features to input array
            for (i in 0 until featureSize) {
                inputArray[frameIndex][i] = if (i < features.size) features[i] else 0f
            }
        }
        
        return inputArray
    }
    
    private fun convertSequenceToByteBuffer(sequence: List<Bitmap>): ByteBuffer {
        // Input shape: [1, sequence_length, height, width, channels]
        val byteBuffer = ByteBuffer.allocateDirect(
            4 * 1 * sequenceLength * inputImageHeight * inputImageWidth * 3
        )
        byteBuffer.order(ByteOrder.nativeOrder())
        
        for (frameIndex in 0 until sequenceLength) {
            val bitmap = if (frameIndex < sequence.size) {
                sequence[frameIndex]
            } else {
                // If we don't have enough frames, repeat the last frame
                sequence.lastOrNull() ?: return byteBuffer
            }
            
            val intValues = IntArray(inputImageWidth * inputImageHeight)
            bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            
            var pixel = 0
            for (i in 0 until inputImageHeight) {
                for (j in 0 until inputImageWidth) {
                    val pixelValue = intValues[pixel++]
                    
                    // Normalize pixel values to [0, 1]
                    byteBuffer.putFloat(((pixelValue shr 16) and 0xFF) / 255.0f) // R
                    byteBuffer.putFloat(((pixelValue shr 8) and 0xFF) / 255.0f)  // G
                    byteBuffer.putFloat((pixelValue and 0xFF) / 255.0f)          // B
                }
            }
        }
        
        return byteBuffer
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
    
    // Method to process a complete video sequence (for recorded videos)
    fun classifyVideoSequence(bitmaps: List<Bitmap>): String {
        return try {
            if (bitmaps.size < sequenceLength) {
                return "Video too short: ${bitmaps.size} frames, need $sequenceLength"
            }
            
            // Take evenly spaced frames if we have more than needed
            val selectedFrames = if (bitmaps.size == sequenceLength) {
                bitmaps
            } else {
                selectFramesFromVideo(bitmaps, sequenceLength)
            }
            
            // Extract features from all frames
            val featureSequence = selectedFrames.map { bitmap -> 
                extractFeaturesFromBitmap(bitmap) 
            }
            
            // Run inference
            interpreter?.let { interpreter ->
                val inputBuffer = convertSequenceToBuffer(featureSequence)
                
                val outputShape = interpreter.getOutputTensor(0).shape()
                val outputBuffer = Array(1) { FloatArray(outputShape[1]) }
                
                interpreter.run(inputBuffer, outputBuffer)
                
                val predictions = outputBuffer[0]
                val maxIndex = predictions.indices.maxByOrNull { predictions[it] } ?: -1
                
                if (maxIndex >= 0 && maxIndex < labels.size) {
                    val confidence = predictions[maxIndex]
                    "${labels[maxIndex]} (${String.format("%.2f", confidence * 100)}%)"
                } else {
                    "Unknown"
                }
            } ?: "Model not loaded"
            
        } catch (e: Exception) {
            e.printStackTrace()
            "Video classification error: ${e.message}"
        }
    }
    
    // Method to process feature vectors directly (if you have pre-extracted features)
    fun classifyFeatureSequence(featureSequence: Array<FloatArray>): String {
        return try {
            if (featureSequence.size != sequenceLength) {
                return "Invalid sequence length: ${featureSequence.size}, expected $sequenceLength"
            }
            
            interpreter?.let { interpreter ->
                val outputShape = interpreter.getOutputTensor(0).shape()
                val outputBuffer = Array(1) { FloatArray(outputShape[1]) }
                
                interpreter.run(featureSequence, outputBuffer)
                
                val predictions = outputBuffer[0]
                val maxIndex = predictions.indices.maxByOrNull { predictions[it] } ?: -1
                
                if (maxIndex >= 0 && maxIndex < labels.size) {
                    val confidence = predictions[maxIndex]
                    "${labels[maxIndex]} (${String.format("%.2f", confidence * 100)}%)"
                } else {
                    "Unknown"
                }
            } ?: "Model not loaded"
            
        } catch (e: Exception) {
            e.printStackTrace()
            "Feature classification error: ${e.message}"
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
        frameBuffer.clear()
    }
}
