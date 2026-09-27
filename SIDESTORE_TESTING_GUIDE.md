# SideStore Auto Sync Testing Guide

## Quick Start

**Branch:** `cursor/autosync-capture-fix-20260925-9bb8`  
**Latest Commit:** `10ed82b8`  
**What Changed:** AVAsset dual-decode audio capture (ReplayKit replacement)

## What to Expect

✅ **Should work:** Auto Sync on SideStore with AVAsset method  
✅ **No entitlements needed:** Standard sideload should work  
✅ **No red indicator:** No screen recording involved  
✅ **Fast startup:** ~100ms instead of 2-8s  

## Test Procedure

### 1. Build & Deploy

```bash
# Build the latest code
cd NuvioMobile-Enhanced
git checkout cursor/autosync-capture-fix-20260925-9bb8
git pull
# Build IPA via Xcode or your build script
# Sideload via SideStore
```

### 2. Basic Playback Test

1. Open app on SideStore device
2. Play any video with audio
3. **Verify:** Video plays normally
4. **Verify:** Audio is audible

### 3. Auto Sync Test (Critical)

1. **Load media** - local file or HTTP stream
2. **Play video** - let it play for a few seconds
3. **Open subtitles** - go to subtitle settings
4. **Tap Auto Sync** button
5. **Watch logs** (connect via Xcode console or use in-app logs)

### 4. Expected Behavior

**Logs should show:**
```
[MPV/iOS/AudioCapture] Starting audio capture at XXXms
[MPV/iOS/AudioCapture] Using AVAsset dual-decode method (SideStore compatible)
[MPV/iOS/AudioCapture/AVAsset] Starting AVAsset-based audio capture at XXXms
[MPV/iOS/AudioCapture/AVAsset] Setting up AVAsset for file://...
[MPV/iOS/AudioCapture/AVAsset] Found audio track, creating reader
[MPV/iOS/AudioCapture/AVAsset] Audio format: 2ch, 48000Hz
[MPV/iOS/AudioCapture/AVAsset] AVAssetReader started successfully, beginning sample processing
[MPV/iOS/AudioCapture/AVAsset] Captured 50 samples (~5s), latest energy: 0.123456
... (continues for ~15-60s)
[MPV/iOS/AudioCapture] Stopping audio capture
[MPV/iOS/AudioCapture/AVAsset] Successfully captured 150 energy samples over 15.0s using AVAsset decode
```

**UI should show:**
- Progress indicator during capture
- "Analyzing audio..." message
- **Success:** "Sync offset: ±XXXXms" or similar
- **OR** "Low confidence" if audio/subtitle mismatch

### 5. What Success Looks Like

✅ Logs mention "AVAsset" (not "ReplayKit")  
✅ "Successfully captured N energy samples"  
✅ N > 0 (typically 150+ for 15s capture)  
✅ Auto Sync completes without errors  
✅ Offset suggestion appears (even if low confidence)  

### 6. What Failure Looks Like

❌ "Cannot start capture: media URL is nil"  
❌ "No audio track found in media"  
❌ "Failed to create AVAssetReader"  
❌ Falls back to ReplayKit (which then fails on SideStore)  
❌ Returns 0 samples  

## Troubleshooting

### Issue: "Cannot start capture: media URL is nil"

**Cause:** AVAsset needs the media URL, but it's not available yet

**Fix:** Wait for playback to start before triggering Auto Sync

**Expected:** Should be rare - MPV loads URL before playback

### Issue: "No audio track found in media"

**Cause:** Media file has no audio track (video-only)

**Fix:** Test with different media that has audio

**Expected:** Video files should have audio tracks

### Issue: Falls back to ReplayKit

**Logs show:** "Media URL not available, falling back to ReplayKit method"

**Cause:** AVAsset couldn't access the URL or format unsupported

**Investigation needed:** What media type/URL caused this?

**Workaround:** None for ReplayKit on SideStore (it will fail)

### Issue: 0 samples captured with AVAsset

**Logs show:** "AVAsset capture produced 0 samples"

**Possible causes:**
1. Media has no audio
2. Audio codec not supported by AVAsset
3. URL expired during capture
4. AVAssetReader failed silently

**Debug:** Check earlier logs for "AVAssetReader started successfully"

## Comparison Tests (Optional)

### Test 1: Local File
- Load a local video file
- Run Auto Sync
- **Expected:** AVAsset method, fast startup

### Test 2: HTTP Stream
- Load an HTTP/HTTPS video
- Run Auto Sync
- **Expected:** AVAsset with headers applied

### Test 3: Short Capture
- Start Auto Sync for just 5-10 seconds
- **Expected:** At least 50-100 samples

### Test 4: Long Capture
- Let Auto Sync run for full 60 seconds
- **Expected:** ~600 samples

## Performance Check

While Auto Sync is running:

- **CPU:** Should be low (~1-5% additional)
- **Memory:** No noticeable increase
- **Playback:** Should continue smoothly (MPV unaffected)
- **UI:** Should remain responsive

## Success Criteria

For this implementation to be considered successful:

1. ✅ Auto Sync starts without errors
2. ✅ AVAsset method is used (not ReplayKit)
3. ✅ Captures > 0 energy samples
4. ✅ Returns samples to SubtitleAutoSyncEngine
5. ✅ Sync completes (success or low confidence)
6. ✅ No crashes or hangs
7. ✅ Works on SideStore device

## Reporting Results

Please report back with:

1. **Device:** iPhone model, iOS version
2. **Install method:** SideStore, AltStore, TestFlight, etc.
3. **Media tested:** Local file, HTTP stream, etc.
4. **Logs:** Relevant sections (especially AVAsset tags)
5. **Result:** Success (samples captured) or Failure (error message)
6. **Screenshots:** If UI shows errors or unexpected behavior

## Known Limitations

**This implementation may not work for:**
- DRM-protected streams (will fall back to ReplayKit → fails on SideStore)
- Very unusual audio codecs (will fall back to ReplayKit)
- Expired signed URLs (may fail mid-capture)

**For these cases:** Consider alternative media sources or wait for future improvements

## Next Steps If It Works

1. ✅ Merge to `enhanced` branch
2. Ship to users via SideStore
3. Update documentation
4. Close related issues

## Next Steps If It Doesn't Work

1. Share full logs (especially errors)
2. Identify failure point (URL access, track reading, decode, etc.)
3. Test with different media types
4. Investigate alternative approaches (see hypotheses in original task)

## Alternative Hypothesis Testing (If AVAsset Fails)

If AVAsset doesn't work, we can test other approaches:

### Hypothesis 2: libmpv lavfi astats
- Set `af=lavfi=graph='[aid1]astats=metadata=1'`
- Read metadata via mpv property
- May require iOS libmpv to include lavfi

### Hypothesis 3: ao=pcm dump
- Set `ao=pcm:file=/tmp/audio-dump.pcm`
- Post-process energy from dump
- May cause playback glitches

### Hypothesis 4: Subtitle-only heuristics
- Last resort if audio paths are impossible
- Use subtitle timing patterns only
- Lower accuracy

## Contact

For questions or issues:
- GitHub: SimSalabimse/NuvioMobile-Enhanced
- PR: #13
- Branch: cursor/autosync-capture-fix-20260925-9bb8

---

**Good luck testing! 🚀**
