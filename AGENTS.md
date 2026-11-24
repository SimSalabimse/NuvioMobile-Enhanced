# Agent notes

Existing project instructions stay in force. This file only adds where to read the vendored iOS skills. It does not replace `CONTRIBUTING.md`, `REBUILD_INSTRUCTIONS.md`, or `Docs/nov7-round2-design.md`.

## Design authority

`ios-design-guide` stays the Human Interface Guidelines and design authority. `swiftui-expert-skill` does not replace it and is not a design system. Do not apply either vendored skill to Kotlin Compose. Compose in `commonMain` is not SwiftUI.

## iOS work

Every future brief that touches iOS reads both skills when they apply:

- SwiftUI views, layout, state, navigation, and Instruments traces: `.agents/skills/swiftui-expert-skill/SKILL.md`
- Swift tasks, actors, `Sendable`, and isolation: `.agents/skills/swift-concurrency/SKILL.md`

Open only the reference files the skill points at for the task. Provenance, licenses, and the update steps are in `.agents/skills/PROVENANCE.md`.

The app target is Xcode 27.2. The checked-in Swift language version is still 5.0, with no strict-concurrency or default-actor setting in `iosApp/iosApp.xcodeproj/project.pbxproj`. Do not assume the Xcode 26 new-project defaults described in the concurrency skill.

A short review of the build 206 Swift and iOS delta is in `.agents/skills/ios-build-206-review.md`. That note is a backlog. It is not a license to change the app in the same pass.

Build cleanly and modularly with proper separation of concerns. Never bolt a feature on just because it was asked for: if it doesn't fit naturally, first refactor the part you touch so it does. If that refactor would be large, stop and propose a red-pen design draft instead.

## Branches and gates

`beta` takes every change the gates mark green. `enhanced` moves only when a release is approved. The beta autopilot does not push to `enhanced`.

A pull request into `beta` has these required checks: `build`, `tests`, `ui-sim`, `name-scan`, `secret-scan`, and the aggregate `beta-gate`. GitHub shows them as `beta-gates / <name>`.

Upstream sync reads the latest published release of luqmanfadlli/NuvioMobile-Enhanced. Prereleases count and drafts do not. It compares that tag with `docs/release/upstream-synced.txt`. A new tag is merged, not squashed, onto `upstream-sync/<tag>` from `beta`. A conflict opens one issue labelled `upstream-sync-conflict` and stops. A clean merge opens a pull request into `beta`, then dispatches the beta gates on that branch. A pull request opened with the built-in token does not start other workflows by itself. `workflow_dispatch` with `dry_run` only reports the comparison.

The default branch stays `enhanced`, so the Monday cron in `sync-upstream.yml` does not run. The weekly check dispatches `sync-upstream.yml` on ref `beta`.
