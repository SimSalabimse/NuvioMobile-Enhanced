import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(OrientationLockAppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
                .onOpenURL { url in
                    AppUrlBridgeKt.handleAppUrl(url: url.absoluteString)
                }
                // Headless simulator smoke: prefer SIMCTL_CHILD_* env (reliable),
                // fall back to container UserDefaults. Avoids the system
                // "Open in Nuvio Enhanced?" dialog that blocks simctl openurl.
                .onAppear {
                    let env = ProcessInfo.processInfo.environment
                    let defaults = UserDefaults.standard
                    let markerDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
                    func smokeMark(_ name: String, _ value: String) {
                        guard let dir = markerDir else { return }
                        let line = "\(Date().timeIntervalSince1970) \(name)=\(value)\n"
                        let url = dir.appendingPathComponent("smoke-marker.txt")
                        if let data = line.data(using: .utf8) {
                            if FileManager.default.fileExists(atPath: url.path) {
                                if let handle = try? FileHandle(forWritingTo: url) {
                                    defer { try? handle.close() }
                                    try? handle.seekToEnd()
                                    try? handle.write(contentsOf: data)
                                }
                            } else {
                                try? data.write(to: url)
                            }
                        }
                    }
                    let link = (env["SMOKE_DEEPLINK"]?.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap { $0.isEmpty ? nil : $0 }
                        ?? defaults.string(forKey: "smoke_deeplink")
                    if let link, !link.isEmpty {
                        defaults.removeObject(forKey: "smoke_deeplink")
                        smokeMark("deeplink_scheduled", link)
                        // Wait for Compose gate + pendingDeepLink collector.
                        DispatchQueue.main.asyncAfter(deadline: .now() + 6.0) {
                            smokeMark("deeplink_fired", link)
                            AppUrlBridgeKt.handleAppUrl(url: link)
                        }
                    }
                    let tab = (env["SMOKE_ACTIVATE_TAB"]?.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap { $0.isEmpty ? nil : $0 }
                        ?? defaults.string(forKey: "smoke_activate_tab")
                    if let tab, !tab.isEmpty {
                        defaults.removeObject(forKey: "smoke_activate_tab")
                        smokeMark("tab_scheduled", tab)
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                            smokeMark("tab_fired", tab)
                            NotificationCenter.default.post(
                                name: Notification.Name("nuvio.smoke.activateTab"),
                                object: nil,
                                userInfo: ["tab": tab]
                            )
                        }
                    }
                }
        }
    }
}
