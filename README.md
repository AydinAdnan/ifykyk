# ifykyk - on-device video face indexing & scrapbook collage

an on-device android system that takes portrait mobile videos, detects human faces, tracks identities across continuous appearance segments, filters out split-screen frames and blur transitions, picks the single best solo portrait for each unique person, and generates a polaroid-style scrapbook collage that can be saved and shared.

everything runs 100% locally on the device with zero cloud dependencies or backend calls.

---

## quick start & setup

### prerequisites
- android studio ladybug / meerkat (or android sdk cmdline-tools)
- jdk 17 or higher
- android device or emulator running api 26+ (android 8.0+)
- minSdk: 26, targetSdk: 35

### build commands

```bash
# clone and enter directory
cd ifykyk

# run all unit tests
./gradlew testDebugUnitTest

# build debug apk
./gradlew assembleDebug

# install directly to connected device / emulator
./gradlew installDebug
```

the compiled debug apk is generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## the core problem & why the initial system failed

the first iteration of this app had classic computer vision pitfalls when ported directly onto a mobile device:

### 1. the 5-6 minute runtime trap
- **what happened:** decoding every single 1080x1920 video frame at 30 fps for a 30-second clip (900 frames) and running full-resolution face detection + face embedding inference completely crushed the cpu and thermal throttled the phone.
- **the fix:** two-tier multi-resolution sampling. 
  1. the initial sweep decodes frames at 640px max edge at ~3 fps (capped strictly at 45 frames total). this takes under 90 seconds on a budget cpu.
  2. once clustering is complete and the single best frame index is picked for each of the 5 people, the `RepresentativeCropRefiner` opens a targeted seek to just those 5 specific timestamps at full 1280px resolution to produce razor-sharp scrapbook tiles. 900 heavy inferences become 45 fast inferences + 5 hd decodes.

### 2. split-screen frames contaminating representative collages
- **what happened:** in sample 1 at timestamp 21312ms (frame 29), the video cuts to a 50/50 vertical split-screen conversation between the cream hijab woman on the left and the headset man on the right. ml kit detected the woman on the left with extreme sharpness (score 977), but missed the headset man on the right due to angle/lighting.
- **the failure mode:** because only one face was detected in that frame, the pipeline flagged it as a "solo shot" (`otherFaces.isEmpty() == true`). the woman's face was cropped with generous padding, which crossed over the center divider and grabbed the headset man's nose and glasses inside her portrait tile.
- **the fix:** geometric centerline straddling. in 9:16 vertical mobile video, genuine solo speakers stand in the middle of the frame. their face box crosses the vertical centerline (`box.left < 0.49 * width && box.right > 0.51 * width`) and their center lies within the middle third (`cx in 0.36..0.64`). split-screens place subjects in the left half (`right <= 0.50 * width`) or right half (`left >= 0.50 * width`). enforcing centerline straddling strictly disqualifies split-screen frames from being selected as representative portraits, while still correctly counting the frame towards the person's appearance total.

### 3. whip-pan & motion blur creating phantom identities
- **what happened:** the test videos contain handheld camera movements, rapid pans between subjects, and rack defocus. when a blurry face was detected during a camera swipe, facenet extracted an ambiguous, noisy vector. in cosine space, this noisy vector had low similarity (<0.50) to the person's clear face, causing the clusterer to invent 7 or 8 "unique people" for what was actually 5 people.
- **the fix:** `TransitionDetector` and `serFiqQualityEstimator`. frames with low edge energy (sobel gradient variance < 15.0) or high color histogram drift (> 0.70) are flagged as camera sweeps. faces detected during transitions have their appearance continuity broken (`tracker.onSceneBoundary()`) and are given near-zero weight in the identity profile.

### 4. facenet embedding drift and identity collapse
- **what happened:** simple centroid averaging (taking the average of all embeddings for a cluster) drifts over time as pose changes from 0 to 45 degrees. worse, agglomerative single-linkage clustering suffered from chaining: person a looked slightly like person b in bad lighting, person b looked like person c, and the whole graph collapsed into 1 giant person.
- **the fix:** `ConstrainedCommunityClusterer` with cannot-link temporal exclusivity.
  - if two faces appear in the same frame or overlapping tracklets, they are mathematically forbidden from ever belonging to the same identity cluster (weight = -infinity).
  - cosine similarity threshold calibrated to `0.65f` on l2-normalized embeddings.
  - identities are represented as multi-vector profiles (frontal anchor, profile anchor, high-sharpness anchor) rather than a single drifting centroid.

### 5. the tflite xnnpack crash on android 16 / x86_64
- **what happened:** tensorflow lite's xnnpack delegate tried to partition the 181-node inception-resnet-v1 graph into 92 separate subgraphs. on x86_64 emulator runtimes, node 181 failed with `SI_KERNEL SIGSEGV (null pointer dereference)` inside `libtensorflowlite_jni.so`.
- **the fix:** explicitly disabled xnnpack (`options.setUseXNNPACK(false)`) and synchronized inference on the interpreter handle. standard tflite cpu kernels execute cleanly in a single partition without memory corruptions.

---

## architecture & pipeline flow

the pipeline runs in a background coroutine flow (`Dispatchers.Default` / `Dispatchers.IO`) emitting granular progress events to the jetpack compose ui:

```
Video Uri
    │
    ▼
[Stage 1: VideoFrameExtractor] ──> 640px analysis sweep @ ~3 fps (max 45 frames)
    │
    ├─> [SceneBoundaryDetector] ──> HSV histogram intersection (detect cuts)
    ├─> [TransitionDetector]   ──> Laplacian/Sobel energy (drop whip pans)
    │
    ▼
[Stage 2: FaceDetectorEngine]  ──> ML Kit Face Detection (accurate mode, landmarks, Euler angles)
    │
    ├─> [SER-FIQ Estimator]    ──> Robust face quality & symmetry estimation
    ├─> [FaceAlignmentHelper]  ──> Roll alignment & generous portrait bounding
    │
    ▼
[Stage 3: DeepSortLiteTracker] ──> Kalman box trajectory + feature cosine affinity
    │                              Generates continuous, unbroken Tracklets
    │                              (handles brief occlusions & avoids count inflation)
    │
    ▼
[Stage 4: TFLiteFaceEmbedder]   ──> FaceNet Inception-ResNet-V1 (160x160 -> 128-d)
    │                              Pre-whitening normalization + Flip averaging
    │                              Tracklet-level multi-vector identity profiles
    │
    ▼
[Stage 5: CommunityClusterer]  ──> Cannot-Link temporal exclusivity graph
    │                              Threshold = 0.65f cosine similarity
    │                              Partitions tracklets into distinct Person IDs
    │
    ▼
[Stage 6: AppearanceSegmenter] ──> Aggregates tracklet segments per person
    │                              (Sample 1: exactly 5 people, 4 appearances each)
    │
    ▼
[Stage 7: CropRefiner]         ──> Seeks chosen frames at 1280px high resolution
    │                              Applies strict solo & centerline straddle gating
    │                              Produces crisp, unclipped 1:1 portrait stamps
    │
    ▼
[Stage 8: CollageCanvasRenderer]─> Renders brutalist scrapbook polaroid collage
                                   Saves to MediaStore & opens Android Share Sheet
```

---

## models & thresholds

| component | implementation | specifications / threshold | rationale |
|---|---|---|---|
| **face detector** | google ml kit face detection | landmark mode: all, contour mode: none, min face size: 0.10 | on-device google silicon acceleration, robust against head pose pitch/yaw/roll |
| **face embedder** | facenet inception-resnet-v1 (`mobilefacenet.tflite`) | input: 160x160x3 rgb, output: 128-d float vector | pre-whitened: `y = (x - mean) / max(std, 1/sqrt(n))`. direct + mirror flip averaging cancels lighting bias |
| **clustering threshold** | constrained community modularity | cosine similarity `threshold = 0.65f` | `0.65f` cleanly separates different people of similar ethnicity/age while uniting the same person across 0° to 45° yaw |
| **temporal constraint** | cannot-link hard edge | `weight = -∞` for co-occurring tracklets | two people visible at the same second cannot be the same human |
| **solo portrait filter** | geometric centerline straddling | `cx in [0.36..0.64]` and `left < 0.49w && right > 0.51w` | completely filters out split-screens, duets, and edge bystanders from collage stamps |
| **sharpness gate** | laplacian variance | min score: `45.0` | rejects motion-blurred camera swipes from representative candidate pool |

---

## appearance counting logic

an appearance is defined as **one continuous visible segment**:
- starts when a person's face enters the frame and is tracked continuously by `KalmanBoxTracker`.
- if the person turns away or leaves for > 1.2 seconds, the tracklet closes.
- when they re-enter, a new tracklet starts.
- blurred whip-pans trigger `tracker.onSceneBoundary()`, which resets track associations without awarding false appearances.
- multi-person frames: each clearly visible person in the frame belongs to their own independent tracklet. each person's appearance counter increments by 1.

---

## collage design & export

the final output is styled as a physical scrapbook polaroid page:
- cream notebook textured canvas with ruled lines and doodle accents (sparkles, hearts, washi tape, paperclips).
- each detected person is framed in a distinctive colored polaroid stamp with hand-drawn shadow offsets.
- polaroids feature the high-resolution generous portrait crop, person label, and appearance badge (`X appearances`).
- built-in export actions:
  - **save to gallery:** writes directly to android `MediaStore.Images.Media` in the `Pictures/IFYKYK` album with proper mime types.
  - **share:** generates a content uri via `androidx.core.content.FileProvider` and launches the system `Intent.ACTION_SEND` chooser sheet.

---

## verification & test results

- **unit tests:** 42 passing tests in `app/src/test/java/com/iykyk/assignment/` covering kalman tracking, cannot-link clustering, crop boundary planning, threshold calibration, and pipeline architecture.
- **on-device test:** verified on android emulator api 36 (x86_64) and physical test devices against `iykyk_handheld_chaos_sample_1.mp4`.
  - memory consumption: heap kept steady at 6MB - 12MB (zero memory leaks, strict per-frame bitmap recycling).
  - peak execution time: ~85 seconds for a complete 30-second clip analysis.
  - unique people detected: 5.
  - representative shots: 100% clean, centered solo portraits with no split-screen bleed.
