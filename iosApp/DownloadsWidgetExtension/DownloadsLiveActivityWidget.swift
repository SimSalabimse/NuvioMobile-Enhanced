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
        /// Estimated bar: empty at start, full at end, at the current speed. Nil = static bar.
        var estimatedStart: Date? = nil
        var estimatedEnd: Date? = nil
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
            // Expanded island, laid out like Apple's own activities:
            // - leading: a 26 pt arrow.down.circle.fill, a little in from the top-left curve;
            // - trailing: the percent in a fixed-width slot, or a small ring that fills with the
            //   estimate. Text(timerInterval:) reserves width for its longest string, so in the
            //   narrow trailing slot it truncated ("5:…"); the countdown moved to the bottom row;
            // - center: title and episode line;
            // - bottom: bar, then size + moon on the left and the countdown on the right in a
            //   fixed 56 pt slot that fits "0:00:00", 16 pt in from the sides and clear of the
            //   bottom corners.
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Image(systemName: "arrow.down.circle.fill")
                        .font(.system(size: 26, weight: .regular))
                        .foregroundStyle(.white, appBlue)
                        .frame(width: 28, height: 28)
                        .padding(.leading, 6)
                        .padding(.top, 4)
                        .accessibilityLabel("Downloading")
                }
                DynamicIslandExpandedRegion(.trailing) {
                    ActivityProgressBadge(context: context, tint: appBlue, ringSize: 24, font: .callout.monospacedDigit().weight(.semibold), textWidth: 52)
                        .foregroundStyle(.white)
                        .padding(.trailing, 6)
                        .padding(.top, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(spacing: 1) {
                        Text(context.attributes.title)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                            .truncationMode(.tail)
                        Text(context.state.detail ?? context.attributes.subtitle)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.7))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                            .truncationMode(.tail)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 2)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    let size = activitySizeParts(context.state.transferredText)
                    VStack(alignment: .leading, spacing: 6) {
                        ActivityProgressBar(context: context, tint: appBlue)
                        HStack(alignment: .firstTextBaseline, spacing: 4) {
                            Text(size.text)
                                .font(.caption2.monospacedDigit())
                                .foregroundStyle(.white.opacity(0.7))
                                .lineLimit(1)
                                .minimumScaleFactor(0.75)
                                .truncationMode(.tail)
                            if size.inBackground {
                                Image(systemName: "moon.fill")
                                    .font(.system(size: 9, weight: .semibold))
                                    .foregroundStyle(.white.opacity(0.6))
                                    .accessibilityLabel("in background")
                            }
                            Spacer(minLength: 6)
                            ActivityCountdown(context: context, font: .caption2.monospacedDigit().weight(.semibold), width: 56)
                                .foregroundStyle(.white.opacity(0.85))
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.top, 4)
                    .padding(.bottom, 8)
                }
            } compactLeading: {
                Image(systemName: "arrow.down.circle.fill")
                    .font(.system(size: 18, weight: .regular))
                    .foregroundStyle(.white, appBlue)
                    .accessibilityLabel("Downloading")
            } compactTrailing: {
                // About 40 pt: a ring with the estimate, otherwise "100%" fits at caption2.
                ActivityProgressBadge(context: context, tint: appBlue, ringSize: 18, font: .caption2.monospacedDigit().weight(.semibold), textWidth: 34)
                    .foregroundStyle(appBlue)
            } minimal: {
                ActivityMinimalView(context: context, tint: appBlue)
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
                Image(systemName: "arrow.down.circle.fill")
                    .font(.system(size: 28, weight: .regular))
                    .foregroundStyle(.white, Color(red: 30.0 / 255.0, green: 136.0 / 255.0, blue: 229.0 / 255.0))
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
                // Fixed width that fits "0:00:00" at headline size, so the countdown never truncates.
                ActivityTrailingLabel(context: context, font: .headline.monospacedDigit().weight(.semibold), width: 84)
                    .foregroundStyle(.white)
            }

            ActivityProgressBar(context: context, tint: .white)

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

/// The estimate to draw, or nil for the static bar: none was sent, or iOS marked the activity
/// stale because the estimated finish passed without an update.
@available(iOSApplicationExtension 16.1, *)
private func activityEstimate(_ context: ActivityViewContext<DownloadsLiveActivityAttributes>) -> ClosedRange<Date>? {
    guard let start = context.state.estimatedStart, let end = context.state.estimatedEnd, end > start else { return nil }
    if activityIsStale(context) { return nil }
    return start...end
}

@available(iOSApplicationExtension 16.1, *)
private func activityIsStale(_ context: ActivityViewContext<DownloadsLiveActivityAttributes>) -> Bool {
    if #available(iOSApplicationExtension 16.2, *) {
        return context.isStale
    }
    return false
}

/// Past the estimated finish with no word from the app: "almost done" rather than a full bar.
@available(iOSApplicationExtension 16.1, *)
private func activityAlmostDone(_ context: ActivityViewContext<DownloadsLiveActivityAttributes>) -> Bool {
    context.state.estimatedEnd != nil && activityIsStale(context)
}

/// The bar. With an estimate it fills on its own (no updates needed while the app is suspended);
/// otherwise it shows the last reported percent.
@available(iOSApplicationExtension 16.1, *)
private struct ActivityProgressBar: View {
    let context: ActivityViewContext<DownloadsLiveActivityAttributes>
    let tint: Color

    var body: some View {
        if let range = activityEstimate(context) {
            ProgressView(timerInterval: range, countsDown: false, label: { EmptyView() }, currentValueLabel: { EmptyView() })
                .progressViewStyle(.linear)
                .tint(tint)
        } else {
            ProgressView(value: activityAlmostDone(context) ? 0.97 : activityNormalizedProgress(context.state.progressPercent))
                .progressViewStyle(.linear)
                .tint(tint)
        }
    }
}

/// Lock Screen top right: the countdown to the estimated finish, "almost done" once it has
/// passed, otherwise the percent. A fixed width, because Text(timerInterval:) sizes itself for
/// its longest string and truncates in anything narrower.
@available(iOSApplicationExtension 16.1, *)
private struct ActivityTrailingLabel: View {
    let context: ActivityViewContext<DownloadsLiveActivityAttributes>
    let font: Font
    let width: CGFloat

    var body: some View {
        Group {
            if let range = activityEstimate(context) {
                Text(timerInterval: range, countsDown: true)
            } else if activityAlmostDone(context) {
                Text("almost done")
            } else {
                Text(activityProgressLabel(context.state.progressPercent))
            }
        }
        .font(font)
        .multilineTextAlignment(.trailing)
        .lineLimit(1)
        .minimumScaleFactor(0.6)
        .frame(width: width, alignment: .trailing)
    }
}

/// Island countdown for the bottom row; empty when there is no estimate (the percent is shown
/// top right instead).
@available(iOSApplicationExtension 16.1, *)
private struct ActivityCountdown: View {
    let context: ActivityViewContext<DownloadsLiveActivityAttributes>
    let font: Font
    let width: CGFloat

    var body: some View {
        if let range = activityEstimate(context) {
            Text(timerInterval: range, countsDown: true)
                .font(font)
                .multilineTextAlignment(.trailing)
                .lineLimit(1)
                .frame(width: width, alignment: .trailing)
        } else if activityAlmostDone(context) {
            Text("almost done")
                .font(font)
                .lineLimit(1)
                .fixedSize()
        }
    }
}

/// Island top right and compact trailing: a ring that fills with the estimate (no text to
/// truncate), otherwise the percent in a fixed-width slot.
@available(iOSApplicationExtension 16.1, *)
private struct ActivityProgressBadge: View {
    let context: ActivityViewContext<DownloadsLiveActivityAttributes>
    let tint: Color
    let ringSize: CGFloat
    let font: Font
    let textWidth: CGFloat

    var body: some View {
        if let range = activityEstimate(context) {
            ProgressView(timerInterval: range, countsDown: false, label: { EmptyView() }, currentValueLabel: { EmptyView() })
                .progressViewStyle(.circular)
                .tint(tint)
                .frame(width: ringSize, height: ringSize)
        } else {
            Text(activityAlmostDone(context) ? "97%" : activityProgressLabel(context.state.progressPercent))
                .font(font)
                .multilineTextAlignment(.trailing)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .frame(width: textWidth, alignment: .trailing)
        }
    }
}

/// Minimal island: a ring that fills with the estimate, the arrow glyph otherwise.
@available(iOSApplicationExtension 16.1, *)
private struct ActivityMinimalView: View {
    let context: ActivityViewContext<DownloadsLiveActivityAttributes>
    let tint: Color

    var body: some View {
        if let range = activityEstimate(context) {
            ProgressView(timerInterval: range, countsDown: false, label: { EmptyView() }, currentValueLabel: { EmptyView() })
                .progressViewStyle(.circular)
                .tint(tint)
        } else {
            Image(systemName: "arrow.down.circle.fill")
                .font(.system(size: 18, weight: .regular))
                .foregroundStyle(.white, tint)
        }
    }
}
