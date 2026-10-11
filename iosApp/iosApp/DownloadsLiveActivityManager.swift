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
    /// Applies run one at a time. Two overlapping applies could end an activity and then
    /// request a new one from an older payload, leaving an activity nothing updates.
    private var applyChain: Task<Void, Never>?

    private init() {}

    func start() {
        guard observer == nil else { return }

        // An activity from an earlier process has no transfer behind it any more.
        // A download that is still running is published again once the downloads list loads.
        endStaleActivitiesFromEarlierLaunch()

        observer = NotificationCenter.default.addObserver(
            forName: downloadsLiveStatusUpdatedNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.syncFromPayloadStore()
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
        guard let payload, payload.status == "Downloading" else {
            for activity in activities where activity.attributes.downloadId == payload?.id {
                await activity.end(dismissalPolicy: .immediate)
            }
            return
        }
        let existing = activities.first { $0.attributes.downloadId == payload.id }

        let state = DownloadsLiveActivityAttributes.ContentState(
            status: payload.status,
            progressPercent: payload.progressPercent,
            transferredText: transferredText(payload)
        )

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
}
