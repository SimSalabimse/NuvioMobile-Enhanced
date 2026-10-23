import Foundation
import SwiftUI
import UIKit

@objc public final class DiagnosticsBridge: NSObject {
    @objc public static let shared = DiagnosticsBridge()
    
    private override init() {}
    
    @objc public static func openDiagnosticsSettings() {
        DispatchQueue.main.async {
            guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
                  let rootViewController = windowScene.windows.first?.rootViewController else {
                return
            }
            
            let settingsView = DiagnosticsSettingsView()
            let hostingController = UIHostingController(rootView: settingsView)
            hostingController.title = "Diagnostics"
            
            if let navigationController = findNavigationController(from: rootViewController) {
                navigationController.pushViewController(hostingController, animated: true)
            } else {
                let navController = UINavigationController(rootViewController: hostingController)
                rootViewController.present(navController, animated: true)
            }
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
    
    private static func findNavigationController(from viewController: UIViewController) -> UINavigationController? {
        if let navController = viewController as? UINavigationController {
            return navController
        }
        
        if let presented = viewController.presentedViewController {
            return findNavigationController(from: presented)
        }
        
        for child in viewController.children {
            if let navController = findNavigationController(from: child) {
                return navController
            }
        }
        
        return nil
    }
}
