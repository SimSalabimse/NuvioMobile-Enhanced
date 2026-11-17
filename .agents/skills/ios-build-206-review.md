# Build 206 iOS review backlog

Build 207 took the three findings in this note: subtitle add runs on the player queue, each share writes a unique file, and the detail host no longer ignores the top safe area for every route.

Reviewed the Swift and iOS delta of `e7e877ce4f484bef34f39100b7f84c7c8f402c15` against `integration/batch11`, using `.agents/skills/swiftui-expert-skill/SKILL.md` and `.agents/skills/swift-concurrency/SKILL.md`. No app code was changed.

Project settings read from `iosApp/iosApp.xcodeproj/project.pbxproj`: `SWIFT_VERSION = 5.0` (line 465) and `IPHONEOS_DEPLOYMENT_TARGET = 16.1` (line 436). `SWIFT_STRICT_CONCURRENCY`, `SWIFT_DEFAULT_ACTOR_ISOLATION`, and `SWIFT_APPROACHABLE_CONCURRENCY` are not set. The concurrency skill's Xcode 26 new-project defaults are not this target.

Kotlin Compose was not reviewed. These skills do not apply to it.

## Findings

1. External subtitle add runs the player command from the URL session callback. `MPVSubtitleRtlFix.prepare` completes on the session queue, and `addSubtitleUrl` then calls `command` (`iosApp/iosApp/Player/MPVPlayerBridge.swift` lines 1787–1794). `command` reads `mpv` before hopping (`lines 2987–2991`), and `withMpvOnQueue` reads it again before `onMpvQueue` (`lines 424–426`). `mpv` is a plain stored pointer (`line 383`) mutated on `mpvQueue`. The sibling `addSubtitle` already enters through `onMpvQueue` (`lines 1804–1806`). Next batch: hop onto `mpvQueue` before any player read, and keep the generation check on that queue.

2. The analysis share sheet writes one temp file on the main queue and can present nothing. `DiagnosticsBridge.shareAnalysisLog` always uses `temporaryDirectory/nuvio-analysis-log.txt` (`iosApp/iosApp/Diagnostics/DiagnosticsBridge.swift` lines 47–55). A second share replaces the file a visible sheet is still using. If `topmostViewController()` is nil, the function returns after the write and does not present. Next batch: use a unique file, and do not leave a silent write when there is no presenter.

3. Every detail destination now ignores the top safe area. `DetailDestinationView.respectsNativeNavigationSafeArea` is the constant `false` (`iosApp/iosApp/ContentView.swift` lines 1733–1735), so the compose host ignores all edges (`lines 1750–1752`). The previous predicate kept the top edge for a folder route. Next batch: confirm a folder screen still clears the navigation bar. This is a layout check, not a concurrency defect.

No other changed Swift file showed a concrete SwiftUI or concurrency defect at this pass. Player queues, the PiP frame lock, and the `@MainActor` parking type were inspected and not added here.
