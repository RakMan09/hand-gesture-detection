# Code Structure Explanation

This section provides a detailed breakdown of the project's codebase organization, explaining the purpose and relationships between different components.

## 📂 Overall Project Organization

```
hand-gesture-detection/
│
├── android/                          # Android Studio Project
│   └── app/src/main/
│       ├── java/com/hci/gesturetouchless/
│       ├── res/                      # UI resources
│       └── assets/                   # Model files
│
└── ml-training/                      # Python ML Pipeline
    ├── notebooks/train.ipynb         # Training code
    ├── src/                          # Python modules
    ├── data/                         # Datasets
    └── models/                       # Trained models
```

---

## 🏗️ Android Application Architecture

### Layer Structure

The Android app follows a **layered architecture** with clear separation of concerns:

```
┌─────────────────────────────────────────┐
│         Presentation Layer              │
│  (UI: Activities, Fragments)            │
├─────────────────────────────────────────┤
│         Business Logic Layer            │
│  (Services, ViewModels, Controllers)    │
├─────────────────────────────────────────┤
│         Model/Data Layer                │
│  (Models, Data Classes, Preferences)    │
├─────────────────────────────────────────┤
│         ML Layer                        │
│  (Gesture Classifier, Landmark Utils)   │
├─────────────────────────────────────────┤
│         Native/System Layer             │
│  (Camera, MediaPipe, Android APIs)      │
└─────────────────────────────────────────┘
```

### Package Structure

```
com.hci.gesturetouchless/
│
├── MainActivity.kt                       # Entry point, foreground detection
│
├── SettingsActivity.kt                   # Settings and configuration
│
├── models/                               # Data classes
│   ├── GestureAction.kt                  # Gesture actions enum
│   ├── GestureType.kt                    # Supported gesture types
│   └── GestureMapping.kt                 # Gesture-to-action mappings
│
├── services/                             # Background services
│   ├── GestureDetectionService.kt        # Background detection
│   └── GestureAccessibilityService.kt    # System-level integration
│
├── ml/                                   # Machine learning
│   └── GestureClassifier.kt              # TensorFlow Lite wrapper
│
└── utils/                                # Helper utilities
    ├── LandmarkUtils.kt                  # Hand landmark processing
    └── PreferencesManager.kt             # Shared preferences
```

---

## 📋 Component Details

### 1. **Presentation Layer** - User Interface

#### MainActivity.kt
**Purpose:** Main application screen with real-time gesture detection
**Key Responsibilities:**
- Display camera preview using CameraX
- Show detected gestures in real-time
- Handle permission requests
- Manage app lifecycle (foreground detection)

**Key Methods:**
```kotlin
onCreate()           → Initialize app, load ML models
onStart()            → Start camera for detection
onStop()             → Stop camera, save battery
startCamera()        → Bind CameraX use cases
bindCameraUseCases() → Connect preview and image analysis
```

**Data Flow:**
```
Camera Frame → CameraX → Image Processor → Landmarks → Classification → UI Update
```

#### SettingsActivity.kt
**Purpose:** Allow users to configure gesture recognition
**Features:**
- Adjust confidence threshold
- Enable/disable specific gestures
- Map gestures to actions
- Toggle accessibility service

---

### 2. **Service Layer** - Background & System Integration

#### GestureDetectionService.kt
**Purpose:** Run gesture detection in background even when app is minimized
**Type:** Foreground Service (required on Android 8+)

**Lifecycle:**
```
onStartCommand() → Initialize camera & models
↓
startDetection() → Continuous gesture monitoring
↓
onDestroy() → Stop camera, clean resources
```

**Key Features:**
- Persistent notification showing service is running
- Auto-restart if killed (START_STICKY)
- Can run alongside foreground app
- Communicates gestures to accessibility service

#### GestureAccessibilityService.kt
**Purpose:** Execute gesture-triggered actions at system level
**Type:** AccessibilityService (requires explicit user permission)

**Integrations:**
- Receives gesture events from detection service
- Executes system actions (volume, screenshot, etc.)
- Handles accessibility event processing
- Integrates with gesture mapping configuration

**Event Flow:**
```
Gesture Detected
    ↓
GestureDetectionService broadcasts
    ↓
GestureAccessibilityService receives
    ↓
Look up action in GestureMapping
    ↓
Execute system action (VOLUME_UP, etc.)
```

---

### 3. **Model Layer** - Data Structures

#### GestureType.kt
**Purpose:** Define supported gesture types
**Structure:**
```kotlin
enum class GestureType {
    THUMBS_UP,      // Thumb pointing up
    PEACE_SIGN,     // Two fingers extended
    FIST,           // Closed hand
    OPEN_PALM,      // All fingers extended
    POINTING        // Index finger extended
}
```
**Usage:** Type-safe gesture reference throughout app

#### GestureAction.kt
**Purpose:** Define possible gesture actions
**Examples:**
```kotlin
enum class GestureAction {
    VOLUME_UP,
    VOLUME_DOWN,
    TAKE_SCREENSHOT,
    OPEN_RECENT_APPS,
    LOCK_SCREEN
}
```

#### GestureMapping.kt
**Purpose:** Map gestures to actions
**Data Structure:**
```kotlin
data class GestureMapping(
    val gesture: String,          // "thumbs_up", "peace_sign", etc.
    val action: GestureAction,    // What to do when gesture detected
    val enabled: Boolean = true   // Is this mapping active?
)
```
**Storage:** Persisted in SharedPreferences via PreferencesManager

---

### 4. **ML Layer** - Core Recognition Engine

#### GestureClassifier.kt
**Purpose:** Wrap TensorFlow Lite model for inference

**Architecture:**
```
Hand Landmarks (63 features)
    ↓
Normalize (using mean/std)
    ↓
TensorFlow Lite Interpreter
    ↓
Dense Layer 1 (256 units, ReLU)
    ↓
Dense Layer 2 (128 units, ReLU)
    ↓
Dense Layer 3 (64 units, ReLU)
    ↓
Output Layer (5 units, Softmax)
    ↓
Probabilities for 5 gestures
```

**Key Methods:**
```kotlin
classify()              → Raw classification (single prediction)
classifyWithSmoothing() → Temporal smoothing (more stable)
resetHistory()          → Clear prediction history
```

**Features:**
- Lazy initialization (models load only when needed)
- Temporal smoothing to reduce false positives
- Configurable confidence threshold
- Automatic normalization using stored mean/std

#### LandmarkUtils.kt
**Purpose:** Process hand landmarks from MediaPipe

**Key Operations:**
```kotlin
normalizeLandmarks()    → Convert 21 landmarks to 63-element feature vector
smoothLandmarks()       → Reduce jitter using Kalman filtering
calculateHandSpread()   → Compute gesture-specific metrics
isValidLandmarks()      → Quality validation
```

**Input:** MediaPipe hand landmark points (21 points × 3 coordinates)
**Output:** Normalized feature vector ready for ML model

---

### 5. **Utility Layer** - Helper Functions

#### PreferencesManager.kt
**Purpose:** Persist user settings using SharedPreferences

**Stored Data:**
```kotlin
- confidenceThreshold    → Min confidence to trigger action (default 0.7)
- enabledGestures        → Which gestures to recognize
- gestureActions         → Custom gesture-to-action mappings
- accessibilityEnabled   → Is background service enabled
```

**Pattern:** Singleton manager for centralized preference access

---

## 🔄 Data Flow Example: Gesture Detection

### 1. Frame Acquisition
```
Device Camera
    ↓
CameraX ImageAnalysis Use Case
    ↓
Image Frame (RGB bitmap)
```

### 2. Hand Detection
```
Image Frame
    ↓
MediaPipe HandLandmarker
    ↓
21 Hand Landmarks (x, y, z normalized [0,1])
```

### 3. Feature Extraction
```
21 Landmarks
    ↓
LandmarkUtils.normalizeLandmarks()
    ↓
63-element Float Array
    ↓
LandmarkUtils.smoothLandmarks()
    ↓
Smoothed 63-element Float Array
```

### 4. Classification
```
Smoothed Features
    ↓
GestureClassifier.classifyWithSmoothing()
    ↓
[0.92, 0.03, 0.02, 0.02, 0.01] ← Probabilities for 5 gestures
    ↓
Apply Confidence Threshold (0.7)
    ↓
Gesture Name + Confidence: ("thumbs_up", 0.92)
```

### 5. Action Execution
```
Gesture Result
    ↓
PreferencesManager.getGestureAction()
    ↓
GestureAction.VOLUME_UP
    ↓
GestureAccessibilityService.performAction()
    ↓
System Action Executed
```

---

## 📦 Dependencies & Their Roles

### Android Framework
- **CameraX** (camera input) - Modern camera API with lifecycle awareness
- **AndroidX** (core libraries) - Compatibility and modern Android components
- **MediaPipe** (landmark detection) - Google's hand detection model

### ML/TensorFlow
- **TensorFlow Lite** (model inference) - Lightweight ML inference on device
- **Gson** (JSON parsing) - Load gesture labels and normalization parameters

### Data Storage
- **SharedPreferences** - User settings and gesture mappings
- **DataStore** - Modern alternative to SharedPreferences

---

## 🔌 Integration Points

### Camera to ML Pipeline
```
CameraX
    ↓
ImageAnalysis Analyzer
    ↓
Process Frame
    ↓
Extract MediaPipe Landmarks
    ↓
Classify Gesture
    ↓
Update UI / Trigger Action
```

### Settings to Detection
```
User Changes Settings
    ↓
PreferencesManager.save()
    ↓
Settings persisted
    ↓
Detection service reads on next frame
    ↓
Applied immediately
```

### Services Communication
```
MainActivity (Foreground)
    ↓
Stops camera when minimized
    ↓
Signals GestureDetectionService
    ↓
Service takes over camera access
    ↓
GestureAccessibilityService notified
    ↓
Ready to execute actions
```

---

## 🧵 Threading Model

### Main Thread
- UI updates (gesture text, status display)
- Activity lifecycle methods
- User input handling

### Background Threads
```
├── Camera Thread (CameraX)
│   └── Processes video frames continuously
│
├── ML Thread (Gesture Classification)
│   └── Runs TensorFlow Lite inference
│
└── Executor Thread (GestureClassifier)
    └── Lazy initializes ML models
```

**Thread Safety:**
- Use `@Volatile` for shared variables between threads
- Use `synchronized` blocks for critical sections
- Use Coroutines/RxJava for async operations

---

## 💾 Memory Management

### Model Loading
```
GestureClassifier.ensureInitialized()
    ↓
Load TensorFlow Lite model from assets
    ↓
Keep in memory (reused for multiple predictions)
    ↓
Close on app shutdown
```

### Frame Processing
```
Process current frame
    ↓
Extract landmarks
    ↓
Discard frame (GC)
    ↓
Process next frame
```

### Optimization
- Models loaded once and reused
- Temporal smoothing uses fixed-size buffer
- Images discarded after processing
- Resources cleaned in `onDestroy()`

---

## 🔐 Permission Handling

```
Runtime Permissions (Android 6+)
    ├── CAMERA (required for video input)
    └── POST_NOTIFICATIONS (required for foreground service)

Accessibility Service
    └── User explicitly enables in Settings
        → GestureAccessibilityService gains system access

Manifest Permissions
    ├── FOREGROUND_SERVICE (background detection)
    ├── FOREGROUND_SERVICE_CAMERA
    └── MODIFY_AUDIO_SETTINGS (volume control)
```

---

## 📊 Configuration & State

### App State
```
Stored in:
├── SharedPreferences (via PreferencesManager)
│   ├── Confidence threshold
│   ├── Enabled gestures
│   └── Gesture-action mappings
│
├── In-Memory (Instance Variables)
│   ├── Camera provider
│   ├── ML models
│   └── Recent predictions
│
└── Model Assets
    ├── hand_model.tflite (ML classifier)
    ├── hand_landmarker.task (MediaPipe model)
    └── hand_labels.json (gesture labels + normalization)
```

### Lifecycle Transitions
```
APP LAUNCHED
    ↓
MainActivity.onCreate() → Load models
    ↓
MainActivity.onStart() → Start foreground camera
    ↓
APP IN FOREGROUND
    └─ Real-time detection in UI
    ↓
USER MINIMIZES APP
    ↓
MainActivity.onStop() → Stop camera
    ↓
GestureDetectionService.onStart() → Take over camera
    ↓
APP IN BACKGROUND
    └─ Background detection continues
    ↓
USER RETURNS TO APP
    ↓
MainActivity.onStart() → Resume foreground camera
    ↓
GestureDetectionService.onDestroy() → Release background service
    ↓
APP CLOSED
    └─ Clean up resources
```

---

## 🔍 Key Design Patterns

### 1. **Singleton Pattern**
- PreferencesManager - Single instance manages all preferences
- GestureClassifier - Single model instance reused across app

### 2. **Service Pattern**
- GestureDetectionService - Background processing
- GestureAccessibilityService - System integration

### 3. **Observer Pattern**
- Camera frames trigger callbacks
- Gestures notify listeners
- Accessibility events trigger actions

### 4. **Strategy Pattern**
- Different detection strategies (raw vs. smoothed classification)
- Configurable gesture-to-action mapping

### 5. **Lazy Initialization**
- ML models load only when needed
- Reduces startup time and memory usage

---

## 🚀 Performance Considerations

### Optimization Techniques
1. **Frame Skipping** - Process every 2nd/3rd frame if needed
2. **Model Quantization** - Smaller model size, faster inference
3. **Temporal Smoothing** - Reduce false positives
4. **Thread Pooling** - Reuse threads, reduce overhead

### Bottlenecks
```
Camera Frame Acquisition    ~5-8ms
MediaPipe Landmark Detection ~15-25ms
Feature Extraction          ~1-2ms
TensorFlow Inference        ~5-8ms
Post-processing             ~1-2ms
─────────────────────────────────
Total                       ~27-45ms per frame
Effective FPS               23-37 FPS
```

---

## 🧪 Testing Strategy

### Unit Testing
```
GestureClassifier
    ├── Test normalization
    ├── Test classification
    └── Test smoothing

LandmarkUtils
    ├── Test landmark processing
    └── Test validation
```

### Integration Testing
```
MainActivity
    ├── Test camera binding
    ├── Test gesture detection
    └── Test settings integration

Services
    ├── Test background detection
    └── Test action execution
```

### Manual Testing
```
Device Testing
    ├── Real gesture recognition
    ├── Background service
    └── Accessibility integration
```

---

## 📈 Scalability & Extension

### Adding New Gestures
1. Collect training data for new gesture
2. Retrain model in ml-training/
3. Export new TFLite model
4. Update GestureType enum
5. Add new gesture mapping in settings

### Adding New Actions
1. Add to GestureAction enum
2. Implement in GestureAccessibilityService
3. Expose in settings UI
4. Test with various gestures

### Performance Improvements
1. Quantize model to INT8 format
2. Implement frame skipping
3. Use GPU acceleration if available
4. Optimize landmark smoothing

---
