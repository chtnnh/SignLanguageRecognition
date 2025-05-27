# MediaPipe Model Download Instructions

## ❌ IMPORTANT: No Single Holistic Model Available

MediaPipe Tasks API does not currently provide a single `holistic_landmarker.task` file. 
The holistic solution is "coming soon" according to Google's documentation.

## ✅ SOLUTION: Use Individual Models

Download these three models and place them in `app/src/main/assets/`:

### 1. Pose Landmarker Model
**Download:** https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/1/pose_landmarker_heavy.task
**Save as:** `app/src/main/assets/pose_landmarker.task`

### 2. Hand Landmarker Model  
**Download:** https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task
**Save as:** `app/src/main/assets/hand_landmarker.task`

### 3. Face Landmarker Model (Optional - for face landmarks if needed)
**Download:** https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
**Save as:** `app/src/main/assets/face_landmarker.task`

## 📝 REQUIRED CODE CHANGES

I need to update your MediaPipeFeatureExtractor.kt to use separate pose and hand models instead of a single holistic model.

This approach will:
- Use PoseLandmarker for pose detection (33 landmarks)
- Use HandLandmarker for both left and right hand detection (21 landmarks each)
- Combine results to match your training data format exactly

## ⚡ QUICK START

1. **Download the models** using the URLs above
2. **Place them in assets folder** as specified
3. **I'll update the code** to use individual models
4. **Test the app** - it should now work with real MediaPipe detection

The individual models approach will give you the same 226 features as your training data:
- Pose: 25 selected landmarks × 4 values = 100 features
- Left Hand: 21 landmarks × 3 values = 63 features  
- Right Hand: 21 landmarks × 3 values = 63 features
- **Total: 226 features** (matches training exactly)
