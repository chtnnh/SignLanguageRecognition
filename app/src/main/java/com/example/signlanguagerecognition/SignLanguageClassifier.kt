package com.example.signlanguagerecognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
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

class SignLanguageClassifier(private val context: Context) {
    
    private var interpreter: Interpreter? = null
    private var inputImageWidth: Int = 224
    private var inputImageHeight: Int = 224
    private var modelInputSize: Int = 224
    
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
                modelInputSize = it[1] // Assuming NHWC format
                inputImageHeight = it[1]
                inputImageWidth = it[2]
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun classify(image: ImageProxy): String {
        return try {
            val bitmap = imageProxyToBitmap(image)
            classifyBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            "Error processing image"
        }
    }
    
    fun classifyBitmap(bitmap: Bitmap): String {
        return try {
            interpreter?.let { interpreter ->
                // Preprocess the image
                val resizedBitmap = Bitmap.createScaledBitmap(
                    bitmap, inputImageWidth, inputImageHeight, true
                )
                
                val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)
                
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
            "Classification error"
        }
    }
    
    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputImageWidth * inputImageHeight * 3)
        byteBuffer.order(ByteOrder.nativeOrder())
        
        val intValues = IntArray(inputImageWidth * inputImageHeight)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        var pixel = 0
        for (i in 0 until inputImageHeight) {
            for (j in 0 until inputImageWidth) {
                val pixelValue = intValues[pixel++]
                
                // Normalize pixel values to [0, 1] or [-1, 1] depending on your model
                byteBuffer.putFloat(((pixelValue shr 16) and 0xFF) / 255.0f)
                byteBuffer.putFloat(((pixelValue shr 8) and 0xFF) / 255.0f)
                byteBuffer.putFloat((pixelValue and 0xFF) / 255.0f)
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
    
    fun close() {
        interpreter?.close()
    }
}
