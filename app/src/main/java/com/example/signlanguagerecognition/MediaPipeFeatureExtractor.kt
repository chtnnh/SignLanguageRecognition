package com.example.signlanguagerecognition

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker.PoseLandmarkerOptions
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker.HandLandmarkerOptions
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.*

class MediaPipeFeatureExtractor(private val context: Context) {
    
    companion object {
        private const val TAG = "MediaPipeExtractor"
        
        // Pose landmarks to keep (matching training data exactly)
        // Excludes wrists (15,16) and fingers (17-22) since hands are captured separately
        private val POSE_LANDMARKS_TO_KEEP = intArrayOf(
            // Face/Head (0-10)
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            // Shoulders and elbows (11-14)
            11, 12, 13, 14,
            // Lower body (23-32) - skip wrists 15,16 and fingers 17-22
            23, 24, 25, 26, 27, 28, 29, 30, 31, 32
        )
        
        // Feature dimensions (matching training exactly)
        private const val POSE_FEATURES = 25 * 4  // 25 landmarks * 4 values (x,y,z,visibility) = 100
        private const val LEFT_HAND_FEATURES = 21 * 3  // 21 landmarks * 3 coordinates (x,y,z) = 63
        private const val RIGHT_HAND_FEATURES = 21 * 3  // 21 landmarks * 3 coordinates (x,y,z) = 63
        private const val TOTAL_FEATURES = POSE_FEATURES + LEFT_HAND_FEATURES + RIGHT_HAND_FEATURES // = 226
        
        // Model files
        private const val POSE_MODEL_FILE = "pose_landmarker.task"
        private const val HAND_MODEL_FILE = "hand_landmarker.task"
    }
    
    private var poseLandmarker: PoseLandmarker? = null
    private var handLandmarker: HandLandmarker? = null
    private var isInitialized = false    
    fun initialize(): Boolean {
        return try {
            // Initialize Pose Landmarker
            val poseBaseOptions = BaseOptions.builder()
                .setModelAssetPath(POSE_MODEL_FILE)
                .build()
            
            val poseOptions = PoseLandmarkerOptions.builder()
                .setBaseOptions(poseBaseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .build()
            
            poseLandmarker = PoseLandmarker.createFromOptions(context, poseOptions)
            
            // Initialize Hand Landmarker
            val handBaseOptions = BaseOptions.builder()
                .setModelAssetPath(HAND_MODEL_FILE)
                .build()
            
            val handOptions = HandLandmarkerOptions.builder()
                .setBaseOptions(handBaseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setNumHands(2) // Detect both hands
                .build()
            
            handLandmarker = HandLandmarker.createFromOptions(context, handOptions)
            
            isInitialized = true
            
            Log.d(TAG, "MediaPipe Pose + Hand Landmarkers initialized successfully")
            Log.d(TAG, "Feature breakdown: Pose=$POSE_FEATURES, LeftHand=$LEFT_HAND_FEATURES, RightHand=$RIGHT_HAND_FEATURES, Total=$TOTAL_FEATURES")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPipe Landmarkers: ${e.message}", e)
            Log.w(TAG, "Make sure '$POSE_MODEL_FILE' and '$HAND_MODEL_FILE' are in the assets folder")
            isInitialized = false
            false
        }
    }
    
    fun extractFeatures(bitmap: Bitmap): FloatArray {
        if (!isInitialized || poseLandmarker == null || handLandmarker == null) {
            Log.w(TAG, "MediaPipe not initialized, using fallback features")
            return createFallbackFeatures()
        }
        
        return try {
            Log.d(TAG, "Extracting features from ${bitmap.width}x${bitmap.height} bitmap")
            
            // Convert bitmap to ARGB_8888 format if needed (CRITICAL FIX)
            val argbBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                Log.d(TAG, "Converting bitmap from ${bitmap.config} to ARGB_8888")
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
            
            // Convert bitmap to MPImage
            val mpImage = BitmapImageBuilder(argbBitmap).build()
            
            // Run pose detection
            val poseResult = poseLandmarker!!.detect(mpImage)
            
            // Run hand detection
            val handResult = handLandmarker!!.detect(mpImage)
            
            // Extract keypoints matching training format exactly
            val features = extractKeypoints(poseResult, handResult)
            
            Log.d(TAG, "Successfully extracted ${features.size} features (expected $TOTAL_FEATURES)")
            verifyFeatures(features)
            
            features
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting features: ${e.message}", e)
            createFallbackFeatures()
        }
    }
    
    private fun extractKeypoints(poseResult: PoseLandmarkerResult, handResult: HandLandmarkerResult): FloatArray {
        val features = mutableListOf<Float>()
        
        // 1. POSE LANDMARKS (100 features)
        // Extract only the selected landmarks in the exact order as training
        if (poseResult.landmarks().isNotEmpty()) {
            val poseLandmarks = poseResult.landmarks()[0] // First (and usually only) pose detection
            
            for (idx in POSE_LANDMARKS_TO_KEEP) {
                if (idx < poseLandmarks.size) {
                    val landmark = poseLandmarks[idx]
                    features.addAll(listOf(
                        landmark.x(),      // x coordinate (normalized 0-1)
                        landmark.y(),      // y coordinate (normalized 0-1)  
                        landmark.z(),      // z coordinate (depth)
                        landmark.visibility().orElse(1.0f) // visibility score
                    ))
                } else {
                    // Add zeros if landmark is missing
                    features.addAll(listOf(0f, 0f, 0f, 0f))
                }
            }
        } else {
            // No pose detected - add zeros for all pose features
            repeat(POSE_FEATURES) { features.add(0f) }
            Log.d(TAG, "No pose landmarks detected, using zeros")
        }
        
        // 2. HAND LANDMARKS (63 + 63 = 126 features)
        // Separate left and right hands to match training data
        var leftHandFound = false
        var rightHandFound = false
        
        if (handResult.landmarks().isNotEmpty() && handResult.handednesses().isNotEmpty()) {
            for (i in handResult.landmarks().indices) {
                val handLandmarks = handResult.landmarks()[i]
                val handedness = handResult.handednesses()[i]
                
                if (handedness.isNotEmpty()) {
                    val isLeftHand = handedness[0].categoryName() == "Left"
                    
                    if (isLeftHand && !leftHandFound) {
                        // Left hand landmarks (63 features)
                        for (j in 0 until minOf(21, handLandmarks.size)) {
                            val landmark = handLandmarks[j]
                            features.addAll(listOf(landmark.x(), landmark.y(), landmark.z()))
                        }
                        // Pad with zeros if fewer than 21 landmarks
                        val remainingFeatures = 21 - minOf(21, handLandmarks.size)
                        repeat(remainingFeatures * 3) { features.add(0f) }
                        leftHandFound = true
                    } else if (!isLeftHand && !rightHandFound) {
                        // This will be added after left hand processing
                        rightHandFound = true
                    }
                }
            }
        }
        
        // Add left hand features if not found
        if (!leftHandFound) {
            repeat(LEFT_HAND_FEATURES) { features.add(0f) }
            Log.d(TAG, "No left hand landmarks detected, using zeros")
        }
        
        // 3. RIGHT HAND LANDMARKS (63 features)
        if (handResult.landmarks().isNotEmpty() && handResult.handednesses().isNotEmpty()) {
            for (i in handResult.landmarks().indices) {
                val handLandmarks = handResult.landmarks()[i]
                val handedness = handResult.handednesses()[i]
                
                if (handedness.isNotEmpty()) {
                    val isRightHand = handedness[0].categoryName() == "Right"
                    
                    if (isRightHand) {
                        // Right hand landmarks (63 features)
                        for (j in 0 until minOf(21, handLandmarks.size)) {
                            val landmark = handLandmarks[j]
                            features.addAll(listOf(landmark.x(), landmark.y(), landmark.z()))
                        }
                        // Pad with zeros if fewer than 21 landmarks
                        val remainingFeatures = 21 - minOf(21, handLandmarks.size)
                        repeat(remainingFeatures * 3) { features.add(0f) }
                        rightHandFound = true
                        break
                    }
                }
            }
        }
        
        // Add right hand features if not found
        if (!rightHandFound) {
            repeat(RIGHT_HAND_FEATURES) { features.add(0f) }
            Log.d(TAG, "No right hand landmarks detected, using zeros")
        }
        
        // Ensure we have exactly 226 features
        val finalFeatures = features.take(TOTAL_FEATURES).toFloatArray()
        
        if (finalFeatures.size < TOTAL_FEATURES) {
            Log.w(TAG, "Feature count mismatch: got ${finalFeatures.size}, expected $TOTAL_FEATURES")
            // Pad with zeros to reach expected size
            val paddedFeatures = FloatArray(TOTAL_FEATURES)
            finalFeatures.copyInto(paddedFeatures)
            return paddedFeatures
        }
        
        return finalFeatures
    }
    
    private fun createFallbackFeatures(): FloatArray {
        Log.w(TAG, "Using fallback feature extraction (MediaPipe not available)")
        
        val features = FloatArray(TOTAL_FEATURES)
        val random = kotlin.random.Random.Default
        var index = 0
        
        // Pose features (100) - normalized coordinates around center
        repeat(POSE_FEATURES / 4) { // 25 pose landmarks
            features[index++] = 0.5f + random.nextFloat() * 0.2f - 0.1f // x (0.4-0.6)
            features[index++] = 0.5f + random.nextFloat() * 0.4f - 0.2f // y (0.3-0.7)
            features[index++] = random.nextFloat() * 0.1f - 0.05f        // z (-0.05-0.05)
            features[index++] = 0.8f + random.nextFloat() * 0.2f         // visibility (0.8-1.0)
        }
        
        // Left hand features (63) - slightly left of center
        repeat(LEFT_HAND_FEATURES / 3) { // 21 hand landmarks
            features[index++] = 0.4f + random.nextFloat() * 0.2f - 0.1f // x (0.3-0.5)
            features[index++] = 0.5f + random.nextFloat() * 0.2f - 0.1f // y (0.4-0.6)
            features[index++] = random.nextFloat() * 0.1f - 0.05f        // z
        }
        
        // Right hand features (63) - slightly right of center
        repeat(RIGHT_HAND_FEATURES / 3) { // 21 hand landmarks
            features[index++] = 0.6f + random.nextFloat() * 0.2f - 0.1f // x (0.5-0.7)
            features[index++] = 0.5f + random.nextFloat() * 0.2f - 0.1f // y (0.4-0.6)
            features[index++] = random.nextFloat() * 0.1f - 0.05f        // z
        }
        
        return features
    }
    
    private fun verifyFeatures(features: FloatArray) {
        Log.d(TAG, "=== FEATURE VERIFICATION ===")
        Log.d(TAG, "Total features: ${features.size} (expected: $TOTAL_FEATURES)")
        
        if (features.size == TOTAL_FEATURES) {
            Log.d(TAG, "✅ Feature count matches training expectation")
        } else {
            Log.e(TAG, "❌ Feature count mismatch! This will cause model errors.")
        }
        
        val minVal = features.minOrNull() ?: 0f
        val maxVal = features.maxOrNull() ?: 0f
        Log.d(TAG, "Feature range: $minVal to $maxVal")
        
        if (features.size >= TOTAL_FEATURES) {
            Log.d(TAG, "Pose features (first 8): ${features.take(8)}")
            Log.d(TAG, "Left hand features (first 6): ${features.drop(POSE_FEATURES).take(6)}")
            Log.d(TAG, "Right hand features (first 6): ${features.drop(POSE_FEATURES + LEFT_HAND_FEATURES).take(6)}")
        }
        
        val nonZeroCount = features.count { it != 0f }
        Log.d(TAG, "Non-zero features: $nonZeroCount/${features.size} (${(nonZeroCount.toFloat() / features.size * 100).toInt()}%)")
        
        if (nonZeroCount < features.size * 0.1) {
            Log.w(TAG, "⚠️ Very few non-zero features detected. Check MediaPipe detection quality.")
        }
    }
    
    fun isReady(): Boolean = isInitialized && poseLandmarker != null && handLandmarker != null
    
    fun release() {
        try {
            poseLandmarker?.close()
            handLandmarker?.close()
            poseLandmarker = null
            handLandmarker = null
            isInitialized = false
            Log.d(TAG, "MediaPipe extractors released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaPipe: ${e.message}", e)
        }
    }
}
