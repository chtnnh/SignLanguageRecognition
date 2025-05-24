# Sign Language Recognition App - Testing Guide

## 📱 **Installation Instructions**

### **For Android Users:**
1. **Download the APK** from the shared link
2. **Enable Unknown Sources:**
   - Settings → Apps → Special Access → Install Unknown Apps
   - Select your browser/file manager → Allow
3. **Install APK:**
   - Tap the downloaded APK file
   - Tap "Install"
   - Grant permissions when prompted

### **Required Permissions:**
The app will request:
- 📷 **Camera** - For sign language detection
- 🎤 **Microphone** - For video recording
- 📁 **Storage** - For saving/loading videos

## 🎯 **How to Test:**

### **Real-time Prediction:**
1. Open the app
2. Point camera at sign language gestures
3. Tap **"Start Prediction"**
4. Wait for 30 frames to collect
5. View prediction results

### **Video File Testing:**
1. Tap **"Select Video File"**
2. Choose a sign language video
3. App extracts 30 frames automatically
4. View prediction results

### **Recording Videos:**
1. Tap **"Start Recording"**
2. Perform sign language
3. Tap **"Stop Recording"**
4. Video saved to device

## 🐛 **What to Report:**

### **App Crashes:**
- When did it crash?
- What were you doing?
- Error messages?

### **Feature Issues:**
- Camera not working?
- Permissions denied?
- Predictions inaccurate?
- UI problems?

### **Performance:**
- App too slow?
- Battery drain?
- Memory issues?

## 📝 **Testing Checklist:**

- [ ] App installs successfully
- [ ] Camera permission granted
- [ ] Camera preview works
- [ ] "Start Prediction" collects frames
- [ ] Frame counter shows progress (0/30 to 30/30)
- [ ] Prediction results appear
- [ ] "Select Video File" works
- [ ] Video recording works
- [ ] "Clear Frame Buffer" works

## 💬 **Feedback:**
Please report:
- Device model and Android version
- Any errors or crashes
- Suggestions for improvement
- Overall experience

**Contact:** [Your contact information]

---
**Note:** This is a beta version for testing. The ML model uses placeholder feature extraction and may not provide accurate predictions until properly configured.
