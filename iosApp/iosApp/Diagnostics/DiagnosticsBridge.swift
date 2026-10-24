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
            hostingController.title = "Diagnostics"
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
