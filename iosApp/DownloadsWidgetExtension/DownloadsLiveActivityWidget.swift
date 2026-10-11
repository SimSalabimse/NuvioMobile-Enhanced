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
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    AccentGlyphView()
                        .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(activityProgressLabel(context.state.progressPercent))
                        .font(.title3.monospacedDigit().weight(.semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.attributes.title)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                        .truncationMode(.tail)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(context.state.detail ?? context.attributes.subtitle)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.82))
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                            .truncationMode(.tail)
                        ProgressView(value: activityNormalizedProgress(context.state.progressPercent))
                            .progressViewStyle(.linear)
                            .tint(appBlue)
                        Text(context.state.transferredText)
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.white.opacity(0.86))
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                            .truncationMode(.tail)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 8)
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

            Text(context.state.transferredText)
                .font(.caption.monospacedDigit())
                .foregroundStyle(.white.opacity(0.86))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
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
