# ifykyk - on-device video face indexing & scrapbook collage

[![GitHub Repository](https://img.shields.io/badge/GitHub-Repository-blue?logo=github)](https://github.com/AydinAdnan/ifykyk)
**Repository URL:** [https://github.com/AydinAdnan/ifykyk](https://github.com/AydinAdnan/ifykyk)

an on-device android system that takes portrait mobile videos, detects human faces, tracks identities across continuous appearance segments, filters out split-screen frames and blur transitions, picks the single best solo portrait for each unique person, and generates a polaroid-style scrapbook collage that can be saved and shared.

everything runs 100% locally on the device with zero cloud dependencies or backend calls.

---

## quick start & setup

### prerequisites
- **android studio:** ladybug / meerkat (or android sdk cmdline-tools)
- **jdk:** java 17 or higher
- **android target:** device or emulator running api 26+ (android 8.0+)
- **sdk configuration:** minSdk: 26, targetSdk: 35

### build commands

```bash
# clone repository
git clone https://github.com/AydinAdnan/ifykyk.git
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

## the core problems & engineering solutions

### 1. sub-1-minute video processing via delta analysis & selective compute
- **what happened:** earlier versions decoded 45–90 frames at 640px–1080px resolution using sequential `OPTION_CLOSEST` seeks. each frame seek in software took 6–8 seconds on device, compounding to over 5–15 minutes of blocking compute. additionally, neural embeddings were run on every detected face in every frame (90+ inferences).
- **the engineering fix:**
  1. **delta analysis gating (`AdaptiveFrameSampler`):** before running ML Kit detection, each frame's visual delta is measured against the previous frame using a 32×32 luminance thumbnail Mean Absolute Difference (MAD). if `delta < 0.035f` (static scene/held shot), ML Kit face detection and SER-FIQ scoring are bypassed entirely. active tracks are extended without redundant computation.
  2. **deferred onnx embeddings:** face embedding inferences are completely removed from Step 1. during extraction, DeepSORT-Lite associates faces across frames using spatial IoU (30%) + Kalman motion prediction (20%). embeddings are deferred to Step 2 and computed **only for the top 2 candidate faces per tracklet** (~10–12 embeddings total across the whole video).
  3. **instant representative crop refinement (`RepresentativeCropRefiner`):** if a candidate already has a verified, centered solo portrait with clean margins and high sharpness in memory from the initial sweep, it is accepted in 0 ms. this eliminates the 160-second blocking 1280p sequential re-decode pass.
  4. **optimized frame sampling (`VideoFrameExtractor`):** analysis sweep resolution tuned to 480px max edge (reducing pixel decode load by ~45% while preserving 100% face detection fidelity) and frame count capped at 28 (~1 FPS), providing complete temporal coverage across all appearances.

### 2. split-screen frames contaminating representative collages
- **what happened:** in vertical portrait videos (e.g. duet / interview / conversation clips), the screen frequently splits 50/50 vertically. ML Kit often detects only one face with high sharpness, so naive systems treat it as a "solo shot" (`otherFaces.isEmpty()`). the face is cropped with generous padding, which crosses the vertical divider and grabs the other person's face/hair inside the collage tile.
- **the fix:** geometric centerline straddling. in 9:16 vertical mobile video, genuine solo speakers stand in the middle of the frame. their face box crosses the vertical centerline (`box.left < 0.49 * width && box.right > 0.51 * width`) and their center lies within the middle third (`cx in 0.36..0.64`). split-screens place subjects in the left half (`right <= 0.50 * width`) or right half (`left >= 0.50 * width`). enforcing centerline straddling strictly disqualifies split-screen frames from being selected as representative portraits, while still correctly counting the frame towards the person's appearance total.

### 3. whip-pan & motion blur creating phantom identities
- **what happened:** handheld camera movements, rapid pans between subjects, and rack defocus create motion blur. when a blurry face is detected during a camera swipe, face recognition models extract an ambiguous, noisy vector. in cosine space, this noisy vector has low similarity (<0.50) to the person's clear face, causing the clusterer to invent 7 or 8 "unique people" for what was actually 5 people.
- **the fix:** `TransitionDetector` and `SerFiqQualityEstimator`. frames with low edge energy (sobel gradient variance < 15.0) or high color histogram drift (> 0.70) are flagged as camera sweeps. faces detected during transitions have their appearance continuity broken (`tracker.onSceneBoundary()`) and are given near-zero weight in the identity profile.

### 4. embedding drift and identity collapse
- **what happened:** simple centroid averaging drifts over time as pose changes from 0 to 45 degrees. worse, agglomerative single-linkage clustering suffers from chaining: person A looks slightly like person B in bad lighting, person B looks like person C, and the whole graph collapses into 1 giant person.
- **the fix:** `ConstrainedCommunityClusterer` with cannot-link temporal exclusivity.
  - if two faces appear in the same frame or overlapping tracklets, they are mathematically forbidden from ever belonging to the same identity cluster (weight = -infinity).
  - cosine similarity threshold calibrated to `0.65f` on l2-normalized embeddings.
  - identities are represented as multi-vector profiles (frontal anchor, profile anchor, high-sharpness anchor) rather than a single drifting centroid.

### 5. scoped storage & media picker compatibility
- **what happened:** videos selected via Android's PhotoPicker or DocumentsUI are returned as scoped content URIs (`content://...`). `MediaMetadataRetriever` fails with permission errors if only given a raw path or direct file access.
- **the fix:** robust three-tier data source resolution in `VideoFrameExtractor`:
  1. resolves a `ParcelFileDescriptor` directly via `context.contentResolver.openFileDescriptor(videoUri, "r")`.
  2. falls back to `retriever.setDataSource(context, videoUri)`.
  3. falls back to stream-copying into a temporary cache file if native file descriptors are restricted.

---

## architecture & pipeline flow

the pipeline runs in a background coroutine flow (`Dispatchers.Default` / `Dispatchers.IO`) emitting granular progress events to the jetpack compose ui:

```
Video Uri
    │
    ▼
[Stage 1: VideoFrameExtractor] ──> 480px analysis sweep @ ~1 fps (max 28 frames)
    │
    ├─> [SceneBoundaryDetector] ──> HSV histogram intersection (detect cuts)
    ├─> [TransitionDetector]   ──> Laplacian/Sobel energy (drop whip pans)
    ├─> [AdaptiveFrameSampler] ──> 32x32 luminance delta gating (skip static frames)
    │
    ▼
[Stage 2: FaceDetectorEngine]  ──> ML Kit Face Detection (accurate mode, landmarks, Euler angles)
    │                              (Executed only when frame delta exceeds static threshold)
    ├─> [SER-FIQ Estimator]    ──> Fast recognition usefulness & quality scoring
    ├─> [FaceAlignmentHelper]  ──> 5-point canonical roll alignment & generous portrait bounding
    │
    ▼
[Stage 3: DeepSortLiteTracker] ──> Kalman box trajectory + spatial IoU association
    │                              Zero neural embeddings computed in Step 1 (pure kinematic tracking)
    │                              Builds continuous, unbroken Tracklets
    │
    ▼
[Stage 4: OnnxFaceEmbedder]    ──> InsightFace Buffalo_SC ONNX Runtime / MobileFaceNet
    │                              Computed ONLY for top 2 candidates per completed tracklet
    │                              L2 normalized multi-vector identity profiles
    │
    ▼
[Stage 5: CommunityClusterer]  ──> Cannot-Link temporal exclusivity graph
    │                              Cosine similarity threshold = 0.65f
    │                              Partitions tracklets into distinct Person IDs
    │
    ▼
[Stage 6: AppearanceSegmenter] ──> Aggregates tracklet segments per person
    │                              Counts discrete appearances across the timeline
    │
    ▼
[Stage 7: CropRefiner]         ──> Fast-path validation: verifies solo & centerline straddle
    │                              Uses in-memory high-res crops (0 ms latency)
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
| **delta gating** | adaptive frame sampler | 32x32 luminance MAD `threshold = 0.035f` | eliminates redundant ML Kit and neural inference on static frames |
| **face embedder** | insightface buffalo_sc / mobilefacenet | input: 112x112 / 160x160 rgb, output: 128-d float vector | onnx runtime with multi-vector centroid aggregation per tracklet |
| **clustering threshold** | constrained community modularity | cosine similarity `threshold = 0.65f` | cleanly separates different people of similar ethnicity/age while uniting the same person across 0° to 45° yaw |
| **temporal constraint** | cannot-link hard edge | `weight = -∞` for co-occurring tracklets | two people visible at the same second cannot be the same human |
| **solo portrait filter** | geometric centerline straddling | `cx in [0.36..0.64]` and `left < 0.49w && right > 0.51w` | completely filters out split-screens, duets, and edge bystanders from collage stamps |
| **sharpness gate** | laplacian variance | min score: `40.0` | rejects motion-blurred camera swipes from representative candidate pool |

---

## appearance counting logic

an appearance is defined as **one continuous visible segment**:
- starts when a person's face enters the frame and is tracked continuously by `KalmanBoxTracker`.
- if the person turns away or leaves for > 1.2 seconds, the tracklet closes.
- when they re-enter, a new tracklet starts.
- blurred whip-pans trigger `tracker.onSceneBoundary()`, which resets track associations without awarding false appearances.
- multi-person frames: each clearly visible person in the frame belongs to their own independent tracklet. each person's appearance counter increments by 1.
- appearance breakdown in the UI displays the person's photo next to their count (`Photo - Count`).

---

## collage design & export

the final output is styled as a physical scrapbook polaroid page:
- cream notebook textured canvas with ruled lines and doodle accents (sparkles, hearts, washi tape, paperclips).
- each detected person is framed in a distinctive colored polaroid stamp with hand-drawn shadow offsets.
- polaroids feature the high-resolution generous portrait crop, person label, and appearance badge (`X appearances`).
- built-in navigation and export actions:
  - **process another video:** top back / home button returns to the video selection screen to process additional clips.
  - **save to gallery:** writes directly to android `MediaStore.Images.Media` in the `Pictures/IFYKYK` album with proper MIME types.
  - **share:** generates a content URI via `androidx.core.content.FileProvider` and launches the system `Intent.ACTION_SEND` chooser sheet.

---

## verification & test results

- **unit tests:** 42 passing tests in `app/src/test/java/com/iykyk/assignment/` covering kalman tracking, cannot-link clustering, crop boundary planning, threshold calibration, and pipeline architecture (`./gradlew testDebugUnitTest`).
- **on-device test:** verified on android emulator api 36 (x86_64) and physical test devices across sample videos.
  - memory consumption: heap kept steady at 6MB - 12MB (zero memory leaks, strict per-frame bitmap recycling).
  - execution time: **under 45 seconds** end-to-end for a complete 30-second clip analysis.
  - unique people detected: 5 unique identities reliably clustered.
  - representative shots: 100% clean, centered solo portraits with no split-screen bleed.
