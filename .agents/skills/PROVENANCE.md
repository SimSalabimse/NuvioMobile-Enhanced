# Vendored agent skills

Repo-local copies only. Nothing here is installed globally, and nothing here is a build or runtime dependency. The upstream install scripts were not run.

Both licenses are MIT. The copyright notice is in each skill's `LICENSE`. Keep that file with the skill.

## SwiftUI Expert 5.3.0

| | |
| --- | --- |
| Skill | `swiftui-expert-skill` |
| Repository | https://github.com/avdlee/swiftui-agent-skill |
| Tag | `5.3.0` (lightweight tag, object type commit) |
| Commit | `62bd760f005093d1e2e4de5db156e456a5f96400` |
| Commit date | 2026-11-06 08:39:42 +0000 |
| Subject | Bump version to 5.3.0 |
| Version file | upstream `plugin.json` `version` is `5.3.0` at that commit |
| Path | `.agents/skills/swiftui-expert-skill/` |
| License | `.agents/skills/swiftui-expert-skill/LICENSE` |

The copy is the tag's `skills/swiftui-expert-skill/` tree: `SKILL.md`, `references/`, `scripts/` (including `instruments_parser/`), and `agents/openai.yaml` with its logo assets. `SKILL.md` loads those references and calls `scripts/record_trace.py` and `scripts/analyze_trace.py`.

Upstream compatibility at this tag: the README covers SDK 27 and Liquid Glass from iOS 26, and `references/latest-apis.md` runs through iOS 27+. `SKILL.md` names Xcode 27.2 for `xctrace triage`. That matches the Xcode 27.2 target. The trace scripts need `xctrace` from Xcode. They are not wired into this app's build.

## Swift Concurrency 2.3.1

| | |
| --- | --- |
| Skill | `swift-concurrency` |
| Repository | https://github.com/avdlee/swift-concurrency-agent-skill |
| Tag | `2.3.1` (lightweight tag, object type commit) |
| Commit | `3b1580d8398e0b5408b3bf0bada0e941a1cfc642` |
| Commit date | 2026-11-06 08:11:43 +0000 |
| Subject | Bump version to 2.3.1 |
| Version file | upstream `plugin.json` `version` is `2.3.1` at that commit |
| Path | `.agents/skills/swift-concurrency/` |
| License | `.agents/skills/swift-concurrency/LICENSE` |

The copy is the tag's `skills/swift-concurrency/` tree: `SKILL.md`, `references/` (including `_index.md`), and `agents/openai.yaml` with its logo assets.

Upstream compatibility at this tag: the README says the skill is Swift 6.2 ready and mentions concurrency-safe notifications from iOS 26. `SKILL.md` describes Xcode 26 new-project defaults (`SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor` and `SWIFT_APPROACHABLE_CONCURRENCY = YES`). It does not name Xcode 27.2. This app does not use those defaults. See `AGENTS.md`.

## Maintenance skill from the same SwiftUI tag

`references/latest-apis.md` tells the agent to run `.agents/skills/update-swiftui-apis/SKILL.md`. That file and `references/scan-manifest.md` are copied from the same SwiftUI tag so the pointer resolves. Same repository, tag, and commit as SwiftUI Expert 5.3.0. License: `.agents/skills/update-swiftui-apis/LICENSE`.

Its own text still tells the agent to edit `skills/swiftui-expert-skill/references/latest-apis.md`. In this repo that file is `.agents/skills/swiftui-expert-skill/references/latest-apis.md`. It also requires the Sosumi MCP. Do not treat a docs refresh as an app change.

## Update

1. Fetch the upstream tag. Do not run `INSTALLATION.md` or any installer.
2. Confirm the tag points at a commit whose `plugin.json` version is the version you intend. If the requested version is not a real tag, stop.
3. Replace only that skill directory with the tag's `skills/<name>/` tree, and replace its `LICENSE` from the same commit.
4. If the SwiftUI tag moves `update-swiftui-apis`, replace `.agents/skills/update-swiftui-apis/` from `.agents/skills/update-swiftui-apis/` at that same commit.
5. Check that every `references/` path named by `SKILL.md` exists, then record the new tag, commit, and version in this file.
6. `AGENTS.md` is listed in `.gitignore`. Stage it with `git add -f AGENTS.md` so the pointer stays in the commit.
