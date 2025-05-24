# Sign Language Recognition Android App

This Android app uses camera to capture sign language gestures and provides real-time recognition using a TensorFlow Lite model. **Updated to handle feature sequences of 30 frames with 1662-dimensional feature vectors.**

## Features

- Camera permission handling
- Real-time camera preview with feature extraction
- Video recording functionality
- **Feature sequence processing (30 vectors of 1662 dimensions)**
- TensorFlow Lite model integration for sign language recognition
- **Real-time prediction with feature buffer management**
- **Video file selection and processing**
- Frame counter display

## Model Requirements

Your TensorFlow Lite model should expect:
- **Input shape: [30, 1662]** (30 frames, 1662 features per frame)
- **30 consecutive feature vectors** from sign language video
- **Feature vector size: 1662 dimensions**
- **Preprocessed feature data** (not raw pixels)

## ⚠️ **IMPORTANT: Feature Extraction Required**

The app currently uses a **placeholder feature extraction method**. You need to implement the actual feature extraction in `SignLanguageClassifier.kt` that matches your model's preprocessing pipeline.

Common approaches for 1662-dimensional features:
- **MediaPipe Hand/Pose keypoints** + derived features
- **CNN feature extraction** from pre-trained networks  
- **Hand-crafted features** (HOG, LBP, motion features)
- **Pose estimation** with augmented features

See `FEATURE_EXTRACTION_GUIDE.md` for detailed implementation guidance.

## Setup Instructions

### 1. Add Your TensorFlow Lite Model

1. Place your `.tflite` model file in the `app/src/main/assets/` folder
2. Name it `sign_language_model.tflite` or update the filename in `SignLanguageClassifier.kt`

### 2. Update Model Labels

In `SignLanguageClassifier.kt`, update the `labels` list to match your model's output classes.

```kotlin
private val labels = listOf(
    // Add your actual sign language labels here
    "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
    "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
    "del", "nothing", "space"
)
```

### 3. Adjust Model Input Size

If your model expects different input dimensions, update these values in `SignLanguageClassifier.kt`:

```kotlin
private var inputImageWidth: Int = 224  // Change to your model's width
private var inputImageHeight: Int = 224 // Change to your model's height
```

### 4. Build and Run

1. Open the project in Android Studio
2. Build the project (Build → Make Project)
3. Run on a physical device (camera functionality requires real device)

## Usage

1. **Grant Permissions**: App will request camera and microphone permissions

2. **Real-time Recognition**: 
   - Tap "Start Prediction" to begin collecting frames
   - Point camera at sign language gestures
   - App collects 30 frames before making prediction
   - Each frame is resized to 300x1662 pixels
   - Prediction appears once 30 frames are collected

3. **Video Recording**: Tap "Start Recording" to record sign language videos

4. **Video File Processing**: 
   - Tap "Select Video File" to choose existing video
   - App extracts exactly 30 frames from the video
   - Frames are resized to 300x1662 and processed by the model

5. **Frame Management**: 
   - Use "Clear Frame Buffer" to reset collected frames
   - Frame counter shows progress (0/30 to 30/30)

## Key Components

- **MainActivity.kt**: Main activity handling camera setup and UI
- **SignLanguageClassifier.kt**: TensorFlow Lite model wrapper for video sequence inference
- **VideoProcessor.kt**: Handles video file processing and frame extraction
- **activity_main.xml**: UI layout with camera preview and controls

## Requirements

- Android 7.0+ (API level 24+)
- Camera permission
- Physical device (emulator camera won't work properly)

## Model Integration Notes

The app is designed to work with image classification models that:
- Accept 224x224 RGB images (adjustable)
- Output probability scores for each class
- Are in TensorFlow Lite format (.tflite)

If your model has different requirements, modify the preprocessing in `SignLanguageClassifier.kt` accordingly.

## Troubleshooting

1. **Camera not working**: Ensure you're testing on a physical device
2. **Model not loading**: Check that your .tflite file is in the assets folder
3. **Incorrect predictions**: Verify your label list matches your model's output classes
4. **Performance issues**: Consider using GPU acceleration or model quantization
