import Foundation
import SwiftUI
import UIKit

@objc public final class DiagnosticsBridge: NSObject {
    @objc public static let shared = DiagnosticsBridge()
    
    private override init() {}
    
    @objc public static func openDiagnosticsSettings() {
        DispatchQueue.main.async {
            guard let presenter = topmostViewController() else { return }

            let hostingController = UIHostingController(rootView: DiagnosticsSettingsView())
            hostingController.title = "Device error log"
            hostingController.navigationItem.rightBarButtonItem = UIBarButtonItem(
                systemItem: .done,
                primaryAction: UIAction { [weak hostingController] _ in
                    hostingController?.dismiss(animated: true)
                }
            )
            presenter.present(UINavigationController(rootViewController: hostingController), animated: true)
        }
    }

    @objc public static func logColdLaunchTime(_ duration: Double) {
        DiagnosticsManager.shared.logColdLaunchTime(duration)
    }
    
    @objc public static func logNetworkError(statusCode: Int, url: String) {
        DiagnosticsManager.shared.logNetworkError(statusCode: statusCode, url: url)
    }
    
    @objc public static func logError(_ message: String) {
        DiagnosticsManager.shared.logError(message: message)
    }
    
    @objc public static func logPlaybackStall(_ duration: Double) {
        DiagnosticsManager.shared.logPlaybackStall(duration: duration)
    }

    @objc public static func logPerformance(_ message: String) {
        DiagnosticsManager.shared.logAnalysisTimings(message)
    }

    /// Share sheet for the analysis log only. Does not write diagnostics.jsonl and does not upload.
    @objc public static func shareAnalysisLog(_ text: String) {
        DispatchQueue.main.async {
            guard let presenter = topmostViewController() else { return }
            let name = "nuvio-share-\(UUID().uuidString).txt"
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
            do {
                try text.write(to: url, atomically: true, encoding: .utf8)
            } catch {
                return
            }
            let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
            if let popover = controller.popoverPresentationController {
                popover.sourceView = presenter.view
                popover.sourceRect = CGRect(
                    x: presenter.view.bounds.midX,
                    y: presenter.view.bounds.midY,
                    width: 1,
                    height: 1
                )
            }
            presenter.present(controller, animated: true)
        }
    }
    
    /// The screen the user is looking at. The app has one navigation stack per tab, so there is
    /// no single navigation controller to push on; presenting from the top keeps it visible.
    private static func topmostViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        let window = scene?.windows.first { $0.isKeyWindow } ?? scene?.windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController, !presented.isBeingDismissed {
            top = presented
        }
        return top
    }
}
