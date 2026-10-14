import BackgroundTasks
import ComposeApp
import Foundation
import UIKit

/// Runs the on-device analysis of finished downloads from a BGProcessingTask (overnight or while
/// charging), and also shortly after launch in the foreground. The task identifier is listed in
/// Info.plist under BGTaskSchedulerPermittedIdentifiers.
enum MediaAnalysisBackgroundTask {
    static let identifier = "com.nuvio.app.media-analysis"

    static func registerAtLaunch() {
        NuvioMediaAnalyzerRegistration.register()
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            guard let processing = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handle(processing)
        }
        MediaAnalysisBackground.shared.appDidLaunch()
        NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { _ in
            schedule()
        }
    }

    static func schedule() {
        let request = BGProcessingTaskRequest(identifier: identifier)
        request.requiresNetworkConnectivity = false
        request.requiresExternalPower = MediaAnalysisBackground.shared.requiresExternalPower()
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            InAppLogBridge.shared.info(tag: "Analysis/iOS", message: "background task not scheduled: \(error)")
        }
    }

    private static func handle(_ task: BGProcessingTask) {
        task.expirationHandler = {
            MediaAnalysisBackground.shared.expireBackgroundTask()
        }
        MediaAnalysisBackground.shared.runBackgroundTask { finished in
            let done = finished.boolValue
            if !done { schedule() }
            task.setTaskCompleted(success: done)
        }
    }
}
