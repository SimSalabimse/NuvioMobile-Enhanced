import ActivityKit
import SwiftUI
import WidgetKit

struct DownloadsLiveActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        let status: String
        let progressPercent: Int
        let transferredText: String
        /// Which episodes are running, e.g. "S1 · E3 + E4 · 2 downloading". Nil for a movie.
        var detail: String? = nil
    }

    let downloadId: String
    let title: String
    let subtitle: String
}

@available(iOSApplicationExtension 16.1, *)
struct DownloadsLiveActivityWidget: Widget {
    private let downloadsUrl = URL(string: "nuvio://downloads")
    private let appBlue = Color(red: 30.0 / 255.0, green: 136.0 / 255.0, blue: 229.0 / 255.0)

    var body: some WidgetConfiguration {
        ActivityConfiguration(for: DownloadsLiveActivityAttributes.self) { context in
            DownloadActivityLockScreenView(context: context)
                .widgetURL(downloadsUrl)
        } dynamicIsland: { context in
            // Expanded island: the leading and trailing regions sit inside the pill's top curve,
            // so they get a few points of inset; the title and episode line go in the center
            // region; the bar and the size line go in the bottom region with 12 pt on each side so
            // neither reaches the bottom corners. "in background" becomes a small moon icon.
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    AccentGlyphView()
                        .padding(.leading, 6)
                        .padding(.top, 2)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(activityProgressLabel(context.state.progressPercent))
                        .font(.callout.monospacedDigit().weight(.semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .padding(.trailing, 6)
                        .padding(.top, 2)
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(spacing: 1) {
                        Text(context.attributes.title)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                            .truncationMode(.tail)
                        Text(context.state.detail ?? context.attributes.subtitle)
                            .font(.caption2)
                            .foregroundStyle(.white.opacity(0.78))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                            .truncationMode(.tail)
                    }
                    .frame(maxWidth: .infinity)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    let size = activitySizeParts(context.state.transferredText)
                    VStack(alignment: .leading, spacing: 4) {
                        ProgressView(value: activityNormalizedProgress(context.state.progressPercent))
                            .progressViewStyle(.linear)
                            .tint(appBlue)
                        HStack(spacing: 4) {
                            Text(size.text)
                                .font(.caption2.monospacedDigit())
                                .foregroundStyle(.white.opacity(0.8))
                                .lineLimit(1)
                                .minimumScaleFactor(0.75)
                                .truncationMode(.tail)
                            if size.inBackground {
                                Image(systemName: "moon.fill")
                                    .font(.system(size: 9, weight: .semibold))
                                    .foregroundStyle(.white.opacity(0.7))
                                    .accessibilityLabel("in background")
                            }
                            Spacer(minLength: 0)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 2)
                }
            } compactLeading: {
                AccentGlyphView()
            } compactTrailing: {
                Text(activityProgressLabel(context.state.progressPercent))
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(appBlue)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .frame(maxWidth: 40)
            } minimal: {
                AccentGlyphView()
            }
            .widgetURL(downloadsUrl)
            .keylineTint(appBlue)
        }
    }
}

private func activityProgressLabel(_ progressPercent: Int) -> String {
    if progressPercent < 0 { return "--%" }
    return "\(max(0, min(100, progressPercent)))%"
}

/// Splits "246,7 MB / 5,65 GB · in background" into the size and a flag, so the expanded island
/// can show the note as an icon instead of spending width on it.
private func activitySizeParts(_ transferredText: String) -> (text: String, inBackground: Bool) {
    let suffix = " · in background"
    if transferredText.hasSuffix(suffix) {
        return (String(transferredText.dropLast(suffix.count)), true)
    }
    return (transferredText, false)
}

private func activityNormalizedProgress(_ progressPercent: Int) -> Double {
    guard progressPercent >= 0 else { return 0 }
    return min(max(Double(progressPercent) / 100.0, 0), 1)
}

/// Lock Screen. The system caps this view at about 160 pt, so it no longer asks for a 168 pt
/// minimum, every line is one line that scales down, and the "Downloading" label is gone (the
/// arrow glyph and the percent already say it).
@available(iOSApplicationExtension 16.1, *)
private struct DownloadActivityLockScreenView: View {
    let context: ActivityViewContext<DownloadsLiveActivityAttributes>

    var body: some View {
        let size = activitySizeParts(context.state.transferredText)
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .center, spacing: 10) {
                AccentGlyphView()
                VStack(alignment: .leading, spacing: 2) {
                    Text(context.attributes.title)
                        .font(.headline.weight(.semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                        .truncationMode(.tail)
                    Text(context.state.detail ?? context.attributes.subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.82))
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                        .truncationMode(.tail)
                }
                .layoutPriority(1)
                Spacer(minLength: 6)
                Text(activityProgressLabel(context.state.progressPercent))
                    .font(.title3.monospacedDigit().weight(.semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .fixedSize()
            }

            ProgressView(value: activityNormalizedProgress(context.state.progressPercent))
                .progressViewStyle(.linear)
                .tint(.white)

            HStack(spacing: 4) {
                Text(size.text)
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.86))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .truncationMode(.tail)
                if size.inBackground {
                    Image(systemName: "moon.fill")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.75))
                        .accessibilityLabel("in background")
                }
                Spacer(minLength: 0)
            }
        }
        // Same idea as the expanded island: a clear inset from the rounded edges on all sides.
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(
            LinearGradient(
                colors: backgroundGradientColors,
                startPoint: .topLeading,
                endPoint: .bottomTrailing,
            )
        )
        .activityBackgroundTint(.clear)
        .activitySystemActionForegroundColor(.white)
    }

    private var backgroundGradientColors: [Color] {
        let accent = Color(red: 30.0 / 255.0, green: 136.0 / 255.0, blue: 229.0 / 255.0)
        return [
            accent.opacity(0.92),
            accent.opacity(0.42),
            Color.black.opacity(0.97),
        ]
    }
}

private struct AccentGlyphView: View {
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(Color(red: 30.0 / 255.0, green: 136.0 / 255.0, blue: 229.0 / 255.0).opacity(0.88))
            Image(systemName: "arrow.down")
                .font(.caption2.bold())
                .foregroundStyle(.white)
        }
        .frame(width: 22, height: 22)
    }
}
