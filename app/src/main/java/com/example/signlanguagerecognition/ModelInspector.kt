package com.example.signlanguagerecognition

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.flex.FlexDelegate
import org.tensorflow.lite.support.common.FileUtil

class ModelInspector(private val context: Context) {
    
    fun inspectModel(): String {
        return try {
            val modelFile = FileUtil.loadMappedFile(context, "sign_language_model.tflite")
            val interpreter = tryCreateInterpreter(modelFile)
            
            if (interpreter == null) {
                return "❌ Failed to create interpreter with all methods"
            }
            
            val inputTensor = interpreter.getInputTensor(0)
            val outputTensor = interpreter.getOutputTensor(0)
            
            val inputShape = inputTensor.shape()
            val outputShape = outputTensor.shape()
            val inputType = inputTensor.dataType()
            val outputType = outputTensor.dataType()
            
            val report = StringBuilder()
            report.append("📊 MODEL INSPECTION REPORT\n\n")
            report.append("✅ Model loaded successfully!\n\n")
            report.append("📥 INPUT TENSOR:\n")
            report.append("   Shape: [${inputShape.joinToString(", ")}]\n")
            report.append("   Type: $inputType\n\n")
            report.append("📤 OUTPUT TENSOR:\n")
            report.append("   Shape: [${outputShape.joinToString(", ")}]\n")
            report.append("   Type: $outputType\n\n")
            
            // Check compatibility with different expected shapes
            when {
                inputShape.contentEquals(intArrayOf(30, 1662)) -> {
                    report.append("✅ Perfect match: [30, 1662]\n")
                }
                inputShape.contentEquals(intArrayOf(1, 30, 1662)) -> {
                    report.append("✅ Batch format: [1, 30, 1662]\n")
                    report.append("   App will adapt to this format\n")
                }
                inputShape.size >= 2 -> {
                    val seq = inputShape[inputShape.size - 2]
                    val feat = inputShape[inputShape.size - 1]
                    report.append("⚠️ Different shape detected\n")
                    report.append("   Last 2 dims: [$seq, $feat]\n")
                    report.append("   Expected: [30, 1662]\n")
                }
                else -> {
                    report.append("❌ Unexpected input shape\n")
                }
            }
            
            interpreter.close()
            report.toString()
            
        } catch (e: Exception) {
            "❌ Model inspection failed: ${e.message}"
        }
    }
    
    private fun tryCreateInterpreter(modelFile: java.nio.MappedByteBuffer): Interpreter? {
        // Try multiple interpreter configurations
        val attempts = listOf(
            "Standard" to { 
                Interpreter.Options().apply {
                    setUseNNAPI(false)
                    setNumThreads(4)
                }
            },
            "Flex Delegate" to {
                Interpreter.Options().apply {
                    addDelegate(FlexDelegate())
                    setUseNNAPI(false)
                    setNumThreads(4)
                }
            },
            "CPU Only" to {
                Interpreter.Options().apply {
                    setUseNNAPI(false)
                    setUseXNNPACK(false)
                    setNumThreads(1)
                }
            }
        )
        
        for ((name, optionsBuilder) in attempts) {
            try {
                val interpreter = Interpreter(modelFile, optionsBuilder())
                Log.d("ModelInspector", "✅ $name interpreter worked")
                return interpreter
            } catch (e: Exception) {
                Log.w("ModelInspector", "❌ $name interpreter failed: ${e.message}")
            }
        }
        
        return null
    }
}
