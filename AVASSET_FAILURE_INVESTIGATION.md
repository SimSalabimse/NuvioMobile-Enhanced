# AVAsset Failure Investigation & Fix Report

## Status: DIAGNOSTIC IMPROVEMENTS DEPLOYED

**Commit SHA: `ec49e7b8`**  
**Branch: `cursor/autosync-capture-fix-20260925-9bb8`**  
**Date: 2026-09-25**

## Critical Discovery

Simen tested IPA from Actions run 36163363845 (commit `ab6737b4`) and reported: **"Didn't work"**

This means AVAsset dual-decode either:
1. Was never attempted (URL resolution failed)
2. Was attempted but failed silently
3. Produced zero samples without clear error indication

## Root Cause Hypotheses

### Hypothesis 1: URL Resolution Failed ⚠️ **MOST LIKELY**

**Problem:** `getCurrentMediaURL()` might return `nil` if:
- `lastLoadRequest` is nil (timing issue)
- URL string can't be parsed by `URL(string:)`
- URL uses custom scheme (ytdl://, etc.)

**Evidence:** Previous implementation had NO logging of:
- Raw URL string before parsing
- Why URL parsing failed
- What scheme was used

**Impact:** If URL is nil, AVAsset is skipped entirely → falls back to ReplayKit → ReplayKit fails on sideload → zero samples

**Fix Applied:**
- Log raw URL string (first 200 chars)
- Log parsed URL components (scheme, host, path)
- Log if scheme is incompatible with AVAsset
- Added scheme check: only file://, http://, https:// are AVAsset-compatible

### Hypothesis 2: Synchronous Track Loading Failed ⚠️ **LIKELY FOR STREAMING**

**Problem:** `asset.tracks(withMediaType: .audio).first` is synchronous and can:
- Block indefinitely for slow network
- Fail silently for streaming URLs (HLS, DASH)
- Timeout with no error message

**Evidence:** Old code comment said "we'll use the sync API for simplicity" - this is wrong for remote media!

**Impact:** AVAsset capture thread hangs or exits early → zero samples

**Fix Applied:**
- Use `asset.loadTracks(withMediaType:)` async API
- Add 10-second timeout
- Log timeout errors explicitly
- Log load errors from AVFoundation

### Hypothesis 3: Unsupported URL Scheme 🔍 **POSSIBLE**

**Problem:** If media URL uses streaming protocols AVFoundation can't handle:
- HLS with DRM
- DASH manifests
- Custom ytdl:// protocol
- Other app-specific schemes

**Evidence:** No scheme validation before attempting AVAsset

**Impact:** AVAsset silently fails → zero samples

**Fix Applied:**
- Check scheme in `getCurrentMediaURL()` and processor
- Only allow file, http, https
- Log warning if scheme is incompatible
- Fall back to ReplayKit for unsupported schemes

### Hypothesis 4: Silent AVAsset Failures 🔍 **POSSIBLE**

**Problem:** Multiple early-return points in AVAsset capture could fail without clear indication:
- `asset.isPlayable` check (not done before)
- AVAssetReader creation failure
- Track loading failure
- Zero samples produced (silent audio)

**Evidence:** Insufficient error logging in each failure path

**Impact:** AVAsset fails but logs don't explain why

**Fix Applied:**
- Added `asset.isPlayable` check
- Enhanced error messages for each failure point
- Log when AVAsset produces zero samples with diagnostic hints
- Log which capture method was actually used

## Changes in Commit `ec49e7b8`

### File 1: `MPVPlayerBridge.swift` - URL Diagnostics

**Before:**
```swift
func getCurrentMediaURL() -> URL? {
    return lastLoadRequest.flatMap { URL(string: $0.urlString) }
}
```

**After:**
```swift
func getCurrentMediaURL() -> URL? {
    guard let request = lastLoadRequest else {
        // Log if lastLoadRequest is nil
        return nil
    }
    
    // Log raw URL string BEFORE parsing
    let urlString = request.urlString
    InAppLogBridge.shared.info(
        tag: "MPV/iOS/AudioCapture",
        message: "getCurrentMediaURL: Raw URL string (first 200 chars): '\(urlString.prefix(200))'"
    )
    
    guard let url = URL(string: urlString) else {
        // Log parse failure
        return nil
    }
    
    // Log parsed components
    let scheme = url.scheme ?? "no-scheme"
    let host = url.host ?? "no-host"
    let path = url.path.isEmpty ? "/" : String(url.path.prefix(80))
    
    InAppLogBridge.shared.info(
        tag: "MPV/iOS/AudioCapture",
        message: "getCurrentMediaURL: Parsed URL - scheme:\(scheme) host:\(host) path:\(path)"
    )
    
    // Warn if scheme incompatible
    let avAssetCompatible = ["file", "http", "https"].contains(scheme.lowercased())
    if !avAssetCompatible {
        InAppLogBridge.shared.warn(
            tag: "MPV/iOS/AudioCapture",
            message: "getCurrentMediaURL: URL scheme '\(scheme)' is NOT compatible with AVAsset"
        )
    }
    
    return url
}
```

### File 2: `AVAssetAudioEnergyCapture.swift` - Async Track Loading

**Before:**
```swift
// Create asset with headers if needed
let asset = createAsset(url: url, headers: requestHeaders)

// Load tracks asynchronously (but we'll use the sync API for simplicity in thread)
guard let audioTrack = asset.tracks(withMediaType: .audio).first else {
    InAppLogBridge.shared.error(
        tag: "MPV/iOS/AudioCapture/AVAsset",
        message: "No audio track found in media"
    )
    captureThread = nil
    return
}
```

**After:**
```swift
// Create asset with headers if needed
let asset = createAsset(url: url, headers: requestHeaders)

// Check if asset is playable first
if !asset.isPlayable {
    InAppLogBridge.shared.error(
        tag: "MPV/iOS/AudioCapture/AVAsset",
        message: "AVAsset reports media is not playable (might be unsupported format or DRM)"
    )
    captureThread = nil
    return
}

// Load tracks with timeout (important for remote URLs)
var audioTrack: AVAssetTrack?
let semaphore = DispatchSemaphore(value: 0)
var loadError: Error?

asset.loadTracks(withMediaType: .audio) { tracks, error in
    audioTrack = tracks?.first
    loadError = error
    semaphore.signal()
}

// Wait up to 10 seconds for track loading (important for remote media)
let timeout = semaphore.wait(timeout: .now() + 10.0)

if timeout == .timedOut {
    InAppLogBridge.shared.error(
        tag: "MPV/iOS/AudioCapture/AVAsset",
        message: "Timed out loading audio tracks after 10s (slow network or unsupported URL)"
    )
    captureThread = nil
    return
}

if let error = loadError {
    InAppLogBridge.shared.error(
        tag: "MPV/iOS/AudioCapture/AVAsset",
        message: "Failed to load audio tracks: \(error.localizedDescription)"
    )
    captureThread = nil
    return
}

guard let track = audioTrack else {
    InAppLogBridge.shared.error(
        tag: "MPV/iOS/AudioCapture/AVAsset",
        message: "No audio track found in media (video-only or unsupported format)"
    )
    captureThread = nil
    return
}
```

### File 3: `MPVAudioCaptureProcessor.swift` - Scheme Validation

**Before:**
```swift
// Try AVAsset method first (works on SideStore)
if let mediaURL = playerViewController?.getCurrentMediaURL(),
   let headers = playerViewController?.getActiveRequestHeaders() {
    InAppLogBridge.shared.info(
        tag: "MPV/iOS/AudioCapture",
        message: "Using AVAsset dual-decode method (SideStore compatible)"
    )
    currentMethod = .avAsset
    avAssetCapture?.startCapture(startTimeMs: startTimeMs, mediaURL: mediaURL, headers: headers)
} else {
    // Fall back to ReplayKit
    startReplayKitCapture()
}
```

**After:**
```swift
// Try AVAsset method first (works on SideStore) if we have a compatible URL
if let mediaURL = playerViewController?.getCurrentMediaURL(),
   let headers = playerViewController?.getActiveRequestHeaders() {
    
    // Check if URL scheme is compatible with AVAsset
    let scheme = mediaURL.scheme?.lowercased() ?? ""
    let isAvAssetCompatible = ["file", "http", "https"].contains(scheme)
    
    if isAvAssetCompatible {
        InAppLogBridge.shared.info(
            tag: "MPV/iOS/AudioCapture",
            message: "Using AVAsset dual-decode method (SideStore compatible) for \(scheme):// URL"
        )
        currentMethod = .avAsset
        avAssetCapture?.startCapture(startTimeMs: startTimeMs, mediaURL: mediaURL, headers: headers)
    } else {
        InAppLogBridge.shared.warn(
            tag: "MPV/iOS/AudioCapture",
            message: "URL scheme '\(scheme)' not compatible with AVAsset, falling back to ReplayKit"
        )
        startReplayKitCapture()
    }
} else {
    // Fall back to ReplayKit if AVAsset is not available
    InAppLogBridge.shared.warn(
        tag: "MPV/iOS/AudioCapture",
        message: "Media URL not available or headers missing, falling back to ReplayKit method"
    )
    startReplayKitCapture()
}
```

## Diagnostic Log Keys for Next Test

When Simen retests with IPA from commit `ec49e7b8`, look for these log messages:

### 1. URL Resolution Logs (Tag: `MPV/iOS/AudioCapture`)

**If URL is nil:**
```
getCurrentMediaURL: lastLoadRequest is nil (no media loaded yet)
```
→ **Diagnosis:** Media not loaded before Auto Sync triggered (timing issue)

**If URL parse fails:**
```
getCurrentMediaURL: Raw URL string (first 200 chars): '...'
getCurrentMediaURL: Failed to parse URL (invalid format or unsupported scheme)
```
→ **Diagnosis:** URL string format incompatible with Foundation.URL

**If scheme incompatible:**
```
getCurrentMediaURL: Parsed URL - scheme:ytdl host:... path:...
getCurrentMediaURL: URL scheme 'ytdl' is NOT compatible with AVAsset
```
→ **Diagnosis:** Custom scheme not supported by AVFoundation

**If scheme compatible:**
```
getCurrentMediaURL: Raw URL string (first 200 chars): 'https://...'
getCurrentMediaURL: Parsed URL - scheme:https host:example.com path:/video.mp4
```
→ **Diagnosis:** URL looks good, continue to next step

### 2. Method Selection Logs (Tag: `MPV/iOS/AudioCapture`)

**If AVAsset used:**
```
Using AVAsset dual-decode method (SideStore compatible) for https:// URL
```
→ **Diagnosis:** AVAsset was attempted

**If scheme rejected:**
```
URL scheme 'ytdl' not compatible with AVAsset, falling back to ReplayKit
```
→ **Diagnosis:** AVAsset skipped due to scheme

**If URL/headers missing:**
```
Media URL not available or headers missing, falling back to ReplayKit method
```
→ **Diagnosis:** AVAsset skipped due to nil URL

### 3. AVAsset Setup Logs (Tag: `MPV/iOS/AudioCapture/AVAsset`)

**If asset not playable:**
```
AVAsset reports media is not playable (might be unsupported format or DRM)
```
→ **Diagnosis:** DRM or unsupported container

**If track loading times out:**
```
Timed out loading audio tracks after 10s (slow network or unsupported URL)
```
→ **Diagnosis:** Network too slow or URL requires special handling

**If track loading fails:**
```
Failed to load audio tracks: [error description]
```
→ **Diagnosis:** AVFoundation can't load this media

**If no audio track:**
```
No audio track found in media (video-only or unsupported format)
```
→ **Diagnosis:** Media has no audio or wrong format

**If reader creation fails:**
```
Failed to create AVAssetReader (unsupported container or codec)
```
→ **Diagnosis:** Container/codec not supported

**If successful:**
```
Found audio track (format: soun), creating reader
AVAssetReader started successfully, beginning sample processing
Audio format: 2ch, 48000Hz
Captured 20 samples (~2s), latest energy: 0.350000
```
→ **Diagnosis:** AVAsset working correctly!

### 4. Capture Completion Logs (Tag: `MPV/iOS/AudioCapture`)

**If AVAsset produced zero samples:**
```
Stopping audio capture (method: AVAsset)
AVAsset capture produced 0 samples. Check logs above for: (1) URL parsing failures, (2) Track loading failures, (3) AVAssetReader errors, (4) No audio track.
```
→ **Diagnosis:** AVAsset failed - check earlier logs

**If AVAsset succeeded:**
```
Stopping audio capture (method: AVAsset)
AVAsset capture succeeded: 150 samples
Successfully captured 150 energy samples using AVAsset decode
```
→ **Diagnosis:** AVAsset worked!

## What Simen Should Test

### Test 1: Basic File Playback

1. Load a **local video file** with audio (file:// URL)
2. Start Auto Sync
3. Check logs for URL resolution
4. Expected: Should work (file:// is AVAsset-compatible)

### Test 2: HTTP Streaming

1. Load an **HTTP/HTTPS video** (not HLS, just progressive download)
2. Start Auto Sync
3. Check logs for track loading (might timeout if slow)
4. Expected: Might work if format is compatible

### Test 3: HLS/Streaming Protocol

1. Load a **streaming URL** (HLS .m3u8, DASH .mpd, etc.)
2. Start Auto Sync
3. Check logs for URL scheme
4. Expected: Might be rejected or fail in track loading

### Test 4: Custom Protocol

1. Load via **ytdl://** or other custom protocol
2. Start Auto Sync
3. Check logs for scheme incompatibility warning
4. Expected: Should fall back to ReplayKit (which fails on sideload)

## Expected Outcomes

### Scenario A: AVAsset Works (Successful Case)

**Logs:**
```
getCurrentMediaURL: Raw URL string: 'file:///path/to/video.mp4'
getCurrentMediaURL: Parsed URL - scheme:file host:no-host path:/path/to/video.mp4
Using AVAsset dual-decode method for file:// URL
Setting up AVAsset for file://...
AVAsset reports media is playable
Loading tracks...
Found audio track, creating reader
AVAssetReader started successfully
Audio format: 2ch, 44100Hz
Captured 20 samples (~2s)
Successfully captured 150 energy samples
```

**User sees:** Sync succeeds, offset applied ✅

### Scenario B: Incompatible URL Scheme

**Logs:**
```
getCurrentMediaURL: Raw URL string: 'ytdl://...'
getCurrentMediaURL: Parsed URL - scheme:ytdl host:... path:...
getCurrentMediaURL: URL scheme 'ytdl' is NOT compatible with AVAsset
URL scheme 'ytdl' not compatible with AVAsset, falling back to ReplayKit
ReplayKit not available on this device (sideload restriction)
```

**User sees:** Error message about unsupported media ❌

### Scenario C: Streaming URL Fails

**Logs:**
```
getCurrentMediaURL: Raw URL string: 'https://example.com/video.m3u8'
getCurrentMediaURL: Parsed URL - scheme:https host:example.com path:/video.m3u8
Using AVAsset dual-decode method for https:// URL
Setting up AVAsset for https://...
AVAsset reports media is not playable
OR
Timed out loading audio tracks after 10s
```

**User sees:** Error message about unsupported media ❌

### Scenario D: URL Resolution Fails

**Logs:**
```
getCurrentMediaURL: lastLoadRequest is nil
Media URL not available, falling back to ReplayKit
ReplayKit not available on this device
```

**User sees:** Error message ❌

**Diagnosis:** Timing issue - media not loaded yet

## Next Steps

1. ✅ **Rebuild IPA** from commit `ec49e7b8`
2. ✅ **Sideload** via SideStore
3. ✅ **Test Auto Sync** on various media types
4. ✅ **Extract logs** via Xcode console or `idevicesyslog`
5. ✅ **Analyze logs** using diagnostic keys above
6. ✅ **Report findings**: Which scenario matched?

## Potential Alternative Solutions (If AVAsset Fundamentally Can't Work)

If logs show AVAsset consistently fails due to:
- Nuvio only uses streaming protocols incompatible with AVAsset
- All media uses custom protocols (ytdl://)
- Network issues make async loading unreliable

Then we need **Alternative Capture Path**:

### Option 1: MPV Audio Filter (af) Export
- Use MPV's audio filter chain to export PCM
- Requires libmpv property access
- Similar to Desktop Windows implementation
- **Pros:** Works for any media MPV can play
- **Cons:** Requires MPV integration changes

### Option 2: MPV Audio Callback Hook
- Hook into MPV's audio output callback
- Capture PCM before AudioUnit playback
- **Pros:** Most reliable, works for everything
- **Cons:** Invasive, requires libmpv modification

### Option 3: System Audio Tap (Private API)
- Use iOS audio tap APIs (if available)
- **Pros:** Universal audio capture
- **Cons:** Might not work on sideload, private API risk

## Current Assessment

**Verdict:** INCONCLUSIVE - Need logs from commit `ec49e7b8` to diagnose

**Most Likely:** URL resolution or scheme compatibility issue

**Next Action:** Rebuild, test, analyze logs → will reveal definitive answer

**Fallback Plan:** If AVAsset fundamentally incompatible with how Nuvio loads media, implement MPV audio filter export (Option 1 above)

---

**Status:** AWAITING RETEST WITH DIAGNOSTIC BUILD `ec49e7b8`

**The logs will tell us everything.**
