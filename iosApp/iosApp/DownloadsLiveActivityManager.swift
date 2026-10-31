import Foundation
import UIKit
#if canImport(ActivityKit) && os(iOS) && !targetEnvironment(macCatalyst)
import ActivityKit
#endif

private let downloadsLiveStatusUpdatedNotification = Notification.Name("NuvioDownloadsLiveStatusUpdated")
private let downloadsLiveStatusPayloadKey = "nuvio.downloads.live_status.payload"

final class DownloadsLiveActivityManager {
    static let shared = DownloadsLiveActivityManager()

    private var observer: NSObjectProtocol?
    private var lifecycleObservers: [NSObjectProtocol] = []
    /// Applies run one at a time. Two overlapping applies could end an activity and then
    /// request a new one from an older payload, leaving an activity nothing updates.
    private var applyChain: Task<Void, Never>?

    private init() {}

    func start() {
        guard observer == nil else { return }

        // An activity from an earlier process has no transfer behind it any more.
        // A download that is still running is published again once the downloads list loads.
        endStaleActivitiesFromEarlierLaunch()
        // The stored payload can name a download from the earlier launch. A running download
        // writes it again on its next progress update.
        UserDefaults.standard.removeObject(forKey: downloadsLiveStatusPayloadKey)

        observer = NotificationCenter.default.addObserver(
            forName: downloadsLiveStatusUpdatedNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.syncFromPayloadStore()
        }

        // While the app is suspended the background session keeps downloading but nothing
        // updates the activity. On the way out the activity gets fresh estimates, so its bar and
        // countdown keep moving on their own; back in the foreground it corrects to real values.
        for name in [
            UIApplication.willResignActiveNotification,
            UIApplication.didEnterBackgroundNotification,
            UIApplication.willEnterForegroundNotification,
            UIApplication.didBecomeActiveNotification,
        ] {
            lifecycleObservers.append(
                NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                    self?.syncFromPayloadStore()
                }
            )
        }

        syncFromPayloadStore()
    }

    private func syncFromPayloadStore() {
#if canImport(ActivityKit) && os(iOS) && !targetEnvironment(macCatalyst)
        guard #available(iOS 16.1, *) else { return }

        var backgroundTaskId: UIBackgroundTaskIdentifier = .invalid
        backgroundTaskId = UIApplication.shared.beginBackgroundTask(withName: "DownloadsLiveActivitySync") {
            UIApplication.shared.endBackgroundTask(backgroundTaskId)
            backgroundTaskId = .invalid
        }

        let previous = applyChain
        applyChain = Task { @MainActor in
            await previous?.value
            // Read the payload now, not when the notification arrived, so the last state wins.
            await self.apply(self.loadPayload())
            if backgroundTaskId != .invalid {
                UIApplication.shared.endBackgroundTask(backgroundTaskId)
                backgroundTaskId = .invalid
            }
        }
#endif
    }

    /// Background URLSession wake: apply the latest payload, wait until the activity has it, then
    /// let iOS suspend the app. The handler used to run first, so a failure that arrived during the
    /// wake never reached the activity and it kept estimating to "almost done".
    func syncThen(_ completion: @escaping () -> Void) {
#if canImport(ActivityKit) && os(iOS) && !targetEnvironment(macCatalyst)
        if #available(iOS 16.1, *) {
            DispatchQueue.main.async {
                self.syncFromPayloadStore()
                let chain = self.applyChain
                Task { @MainActor in
                    await chain?.value
                    completion()
                }
            }
            return
        }
#endif
        DispatchQueue.main.async { completion() }
    }

    private func loadPayload() -> DownloadsLiveStatusPayload? {
        guard let encoded = UserDefaults.standard.string(forKey: downloadsLiveStatusPayloadKey) else {
            return nil
        }
        let data = Data(encoded.utf8)
        return try? JSONDecoder().decode(DownloadsLiveStatusPayload.self, from: data)
    }

#if canImport(ActivityKit) && os(iOS) && !targetEnvironment(macCatalyst)
    @available(iOS 16.1, *)
    private func apply(_ payload: DownloadsLiveStatusPayload?) async {
        let activities = Activity<DownloadsLiveActivityAttributes>.activities

        // End every activity that is not the running download, not just the first one.
        for activity in activities where activity.attributes.downloadId != payload?.id {
            await activity.end(dismissalPolicy: .immediate)
        }
        if let payload, payload.status == "Failed" || payload.status == "Waiting" {
            await endStopped(payload, activities: activities)
            return
        }
        guard let payload, payload.status == "Downloading" || payload.status == "Preparing" else {
            for activity in activities where activity.attributes.downloadId == payload?.id {
                await activity.end(dismissalPolicy: .immediate)
            }
            return
        }
        let existing = activities.first { $0.attributes.downloadId == payload.id }

        let est = estimate(for: payload)
        let state = DownloadsLiveActivityAttributes.ContentState(
            status: payload.status,
            progressPercent: payload.progressPercent,
            transferredText: UIApplication.shared.applicationState == .background
                ? "\(transferredText(payload)) · in background"
                : transferredText(payload),
            detail: payload.detail,
            estimatedStart: est?.start,
            estimatedEnd: est?.end
        )

        if #available(iOS 16.2, *) {
            // Safety net: with no word from the app by the stale date, the widget says
            // "Paused — open Nuvio to check" instead of a bar that keeps moving. With an estimate
            // that is just before the estimated finish; without one, after 15 minutes.
            let staleDate = est?.staleDate ?? Date().addingTimeInterval(15 * 60)
            let content = ActivityContent(state: state, staleDate: staleDate)
            if let existing {
                await existing.update(content)
                return
            }
            let attributes = DownloadsLiveActivityAttributes(
                downloadId: payload.id,
                title: payload.title,
                subtitle: payload.subtitle
            )
            _ = try? Activity<DownloadsLiveActivityAttributes>.request(
                attributes: attributes,
                content: content,
                pushType: nil
            )
            return
        }

        if let existing {
            await existing.update(using: state)
            return
        }

        let attributes = DownloadsLiveActivityAttributes(
            downloadId: payload.id,
            title: payload.title,
            subtitle: payload.subtitle
        )

        _ = try? Activity<DownloadsLiveActivityAttributes>.request(
            attributes: attributes,
            contentState: state,
            pushType: nil
        )
    }
#endif

#if canImport(ActivityKit) && os(iOS) && !targetEnvironment(macCatalyst)
    /// The transfer behind the activity stopped without the user pausing it: show why ("Failed" or
    /// "Waiting" for the app to refresh the link) as the final state and end the activity, so it
    /// stays on the Lock Screen for a while but never keeps estimating.
    @available(iOS 16.1, *)
    private func endStopped(
        _ payload: DownloadsLiveStatusPayload,
        activities: [Activity<DownloadsLiveActivityAttributes>]
    ) async {
        guard let existing = activities.first(where: { $0.attributes.downloadId == payload.id }),
              existing.activityState == .active else { return }
        let state = DownloadsLiveActivityAttributes.ContentState(
            status: payload.status,
            progressPercent: payload.progressPercent,
            transferredText: payload.message ?? transferredText(payload),
            detail: payload.detail,
            estimatedStart: nil,
            estimatedEnd: nil
        )
        let dismissal: ActivityUIDismissalPolicy = .after(Date().addingTimeInterval(2 * 3600))
        if #available(iOS 16.2, *) {
            await existing.end(ActivityContent(state: state, staleDate: nil), dismissalPolicy: dismissal)
        } else {
            await existing.end(using: state, dismissalPolicy: dismissal)
        }
    }
#endif

    private struct Estimate {
        let start: Date
        let end: Date
        let staleDate: Date
    }

    /**
     * Where the bar would be if the smoothed speed holds: it starts at the bytes already done
     * (start = sampled time minus done / speed) and fills at end = sampled time plus remaining /
     * speed. The speed is shaded 5% down so the bar tends to lag, not lead, and the activity goes
     * stale a moment before the bar would be full, so it never shows 100% before the file is done.
     * Nil (static bar) when the size or speed is unknown or the speed is too low to be useful.
     */
    private func estimate(for payload: DownloadsLiveStatusPayload) -> Estimate? {
        guard let total = payload.totalBytes, total > 0,
              let measured = payload.bytesPerSecond, measured >= 32 * 1024,
              let sampledAtMs = payload.sampledAtEpochMs else { return nil }
        let done = max(0, min(payload.downloadedBytes, total))
        let remaining = Double(total - done)
        guard remaining > 0 else { return nil }
        let speed = measured * 0.95
        let remainingSeconds = remaining / speed
        guard remainingSeconds.isFinite, remainingSeconds < 24 * 3600 else { return nil }
        let sampledAt = Date(timeIntervalSince1970: Double(sampledAtMs) / 1000.0)
        let start = sampledAt.addingTimeInterval(-Double(done) / speed)
        let end = sampledAt.addingTimeInterval(remainingSeconds)
        let staleDate = end.addingTimeInterval(-min(2.0, remainingSeconds / 10.0))
        return Estimate(start: start, end: end, staleDate: staleDate)
    }

    private func endStaleActivitiesFromEarlierLaunch() {
#if canImport(ActivityKit) && os(iOS) && !targetEnvironment(macCatalyst)
        guard #available(iOS 16.1, *) else { return }
        let stale = Activity<DownloadsLiveActivityAttributes>.activities
        guard !stale.isEmpty else { return }
        let previous = applyChain
        applyChain = Task { @MainActor in
            await previous?.value
            for activity in stale {
                await activity.end(dismissalPolicy: .immediate)
            }
        }
#endif
    }

    private func transferredText(_ payload: DownloadsLiveStatusPayload) -> String {
        if payload.status == "Preparing" { return "Preparing" }
        let downloaded = formatBytes(payload.downloadedBytes)
        if let total = payload.totalBytes {
            return "\(downloaded) / \(formatBytes(total))"
        }
        return downloaded
    }

    private func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useKB, .useMB, .useGB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
}

#if canImport(ActivityKit) && os(iOS) && !targetEnvironment(macCatalyst)
@available(iOS 16.1, *)
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
#endif

private struct DownloadsLiveStatusPayload: Decodable {
    let id: String
    let title: String
    let subtitle: String
    let status: String
    let downloadedBytes: Int64
    let totalBytes: Int64?
    let progressPercent: Int
    let detail: String?
    let bytesPerSecond: Double?
    let sampledAtEpochMs: Int64?
    let message: String?
}
