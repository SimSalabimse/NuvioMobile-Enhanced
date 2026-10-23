# Nuvio Mobile Performance Optimizations

## Overview
This document describes the 9 mobile performance optimization items implemented for Nuvio, each with an individual on/off toggle and debug timing/memory readout for impact measurement.

**Implemented by:** Simsalabim

**Important:** All functionality is preserved — especially the CJK font support is maintained.

## Settings Location
Settings > Advanced > Performance

## Performance Metrics Dashboard
The Performance settings page includes a metrics dashboard showing:
- **Launch Time**: Time from app start to UI ready (milliseconds)
- **Time to Playback**: Time from app start to first video playback (milliseconds)
- **Memory Usage**: Current app memory usage (MB)

## Optimization Items

### 1. Strip Symbols (Build-Time)
**Toggle:** `performance_symbol_stripping_enabled`

**Description:** Strip debugging symbols at build time to reduce binary size.

**Implementation:** Build-time optimization setting. Enable this toggle as a reminder to build with symbols stripped. Requires rebuild to take effect.

**Impact:** Reduced app size, faster app launch on first install.

**Note:** This is a build-time optimization. The toggle serves as a configuration flag for the build system.

---

### 2. Poster Downsampling + Cache Cap
**Toggle:** `performance_poster_downsampling_enabled`
**Cache Size:** `performance_poster_cache_size_mb` (default: 100 MB)

**Description:** Downsample poster images to appropriate display sizes and cap the image cache.

**Implementation:** 
- Integrated with Coil3 ImageLoader
- Applies memory cache policy when enabled
- Configurable cache size limit (10-500 MB)

**Impact:** Lower memory usage, reduced image loading overhead.

---

### 3. Parallel Startup
**Toggle:** `performance_parallel_startup_enabled`

**Description:** Load app components (repositories, services) in parallel during startup instead of sequentially.

**Implementation:** When enabled, initialize non-dependent services concurrently using coroutine launch blocks.

**Impact:** Faster app launch time.

---

### 4. Cached Home First
**Toggle:** `performance_cached_home_first_enabled`

**Description:** Show cached home screen content immediately while refreshing data in the background.

**Implementation:** Display stale-while-revalidate pattern — show cached data first, update when fresh data arrives.

**Impact:** Perceived faster app startup, immediate content display.

---

### 5. Shared HTTP Client + Request Deduplication + Short Caches
**Toggles:** 
- `performance_request_dedupe_enabled`
- `performance_short_http_caches_enabled`
- `performance_http_cache_ttl_seconds` (default: 60 seconds)

**Description:** 
- Use shared HTTP client with single connection pool
- Deduplicate identical concurrent requests
- Enable short-lived HTTP response caches

**CRITICAL:** TheIntroDB submit and preview endpoints MUST always stay fresh (never cached).

**Implementation:**
- Already uses `sharedApiHttpClient` (maintains single connection pool)
- Add request deduplication layer for concurrent identical requests
- Add TTL-based cache with freshness requirements for TheIntroDB endpoints

**Impact:** Reduced network overhead, faster response times for repeated requests.

---

### 6. Capped LRU Caches
**Toggle:** `performance_capped_lru_caches_enabled`

**Description:** Limit in-memory caches with LRU (Least Recently Used) eviction policy.

**Implementation:** Apply size caps to various caches (metadata, thumbnails, etc.) with LRU eviction.

**Impact:** Controlled memory usage, prevents unbounded cache growth.

---

### 7. Pause Poster Prefetch During Playback
**Toggle:** `performance_pause_prefetch_playback_enabled`

**Description:** Pause **poster image prefetching and catalog refreshes** during active video playback to preserve CPU/network bandwidth for playback quality.

**CRITICAL DISTINCTION:**
- ✅ Pauses: Poster prefetch, catalog refreshes
- ✅ Allowed: Background media analysis (e.g., intro detection)
- ✅ Downloads: Keep existing smart throttle that protects playback
- ❌ Never: Full-speed downloads during playback (existing throttle remains)

**Context:** An earlier build added a download throttle that kicks in when downloads would hurt playback. This optimization does NOT remove that throttle. The existing "throttle downloads when it hurts playback" behavior is preserved and critical for smooth local and remote playback.

**Implementation:** Monitor playback state; when playing, defer poster prefetch and refresh operations.

**Impact:** Smoother playback, reduced network contention during video watching.

---

### 8. Prefetch Next Episode Link
**Toggle:** `performance_next_episode_prefetch_enabled`

**Description:** Prefetch metadata for the next episode when user is on the episode details screen.

**Implementation:** When viewing episode details, preload the next episode's stream links and metadata in the background.

**Impact:** Faster "Play Next" experience, reduced wait time between episodes.

---

### 9. Image Compression + Trim Swift Exports
**Toggle:** `performance_image_compression_enabled`

**Description:** Small optimizations including compressed image assets and trimming unnecessary Swift interface exports.

**Implementation:**
- Ensure images are compressed appropriately
- Remove unused Swift exports from Kotlin/Native interfaces where safe

**Impact:** Smaller binary size, reduced memory overhead.

---

## CJK Font Preservation

**Critical Requirement:** The CJK (Chinese/Japanese/Korean) font support MUST be preserved. This was explicitly requested by Simsalabim: "don't hurt functionality for size."

**Implementation:** All size optimizations explicitly exclude the CJK font files and related resources. No changes to subtitle rendering or text display that would affect CJK characters.

---

## Testing & Validation

### Before Optimization Baseline
1. Go to Settings > Advanced > Performance
2. Disable all optimization toggles
3. Force-close and restart the app
4. Note the baseline metrics:
   - Launch Time
   - Time to Playback
   - Memory Usage

### Per-Optimization Impact
1. Enable ONE optimization toggle
2. Force-close and restart the app
3. Note the new metrics
4. Compare against baseline to measure impact
5. Disable the toggle and repeat for the next optimization

### Combined Impact
1. Enable all optimizations
2. Force-close and restart
3. Measure combined impact

---

## Release Dashboard Tracking

The Performance Metrics readout enables tracking on the release dashboard:
- Measure before/after for each optimization
- Track metrics across app versions
- Identify optimization effectiveness
- Monitor for regressions

---

## Implementation Notes

### File Structure
```
composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/
  ├── PerformanceSettingsStorage.kt         # Storage interface
  ├── PerformanceSettingsRepository.kt      # State management
  ├── PerformanceSettingsPage.kt           # UI components
  └── PerformanceMetrics.kt                # Metrics tracking

composeApp/src/iosMain/kotlin/com/nuvio/app/features/settings/
  ├── PerformanceSettingsStorage.ios.kt    # iOS storage impl
  └── PerformanceMetrics.ios.kt            # iOS memory tracking
```

### String Resources
All user-facing strings are in:
```
composeApp/src/commonMain/composeResources/values/strings.xml
```

### Settings Navigation
- Settings > Advanced > Performance
- Each optimization listed with toggle and description
- Metrics displayed at top of page

---

## Attribution

**Implemented by:** Simsalabim (never use Simen/Jacobsen in the repo)

All optimization work, toggle infrastructure, and metrics tracking designed and implemented for Nuvio Mobile performance measurement and improvement.
