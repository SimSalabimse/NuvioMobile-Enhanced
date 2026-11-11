import UIKit
import UserNotifications
import ComposeApp

private let lockPlayerToLandscapeNotification = Notification.Name("NuvioPlayerLockLandscape")
private let unlockPlayerOrientationNotification = Notification.Name("NuvioPlayerUnlockOrientation")
private let preferPlayerPortraitNotification = Notification.Name("NuvioPlayerPreferPortrait")
private let preferPlayerLandscapeNotification = Notification.Name("NuvioPlayerPreferLandscape")

final class OrientationLockAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        OrientationLockCoordinator.shared.start()
        DownloadsLiveActivityManager.shared.start()
        UNUserNotificationCenter.current().delegate = self
        MediaAnalysisBackgroundTask.registerAtLaunch()
        return true
    }

    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        OrientationLockCoordinator.shared.supportedOrientations
    }

    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        // The handler runs only after the Live Activity has the latest state (a failure included).
        DownloadsPlatformDownloader_iosKt.handleDownloadsBackgroundEvents(
            identifier: identifier,
            completionHandler: {
                DownloadsLiveActivityManager.shared.syncThen(completionHandler)
            }
        )
    }

    func applicationWillResignActive(_ application: UIApplication) {
        // Still in the foreground here: transfers started now keep full priority while locked.
        DownloadsPlatformDownloader_iosKt.prepareDownloadsForAppBackground()
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        DownloadsPlatformDownloader_iosKt.pauseDownloadsForAppBackground()
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if let deepLink = response.notification.request.content.userInfo["deeplink"] as? String {
            AppUrlBridgeKt.handleAppUrl(url: deepLink)
        }
        completionHandler()
    }
}

@MainActor
final class OrientationLockCoordinator {
    static let shared = OrientationLockCoordinator()

    private(set) var supportedOrientations: UIInterfaceOrientationMask = .allButUpsideDown
    private var observers: [NSObjectProtocol] = []
    private var landscapeLockRequested = false
    private var parkedPlayerSuspension = false
    private var playerPrefersPortrait = false

    private init() {}

    func start() {
        guard observers.isEmpty else { return }

        let center = NotificationCenter.default
        observers.append(
            center.addObserver(forName: lockPlayerToLandscapeNotification, object: nil, queue: .main) { [weak self] _ in
                // `queue: .main` runs this block on the main thread, which is the main actor.
                MainActor.assumeIsolated { self?.setLandscapeLock(enabled: true) }
            }
        )
        observers.append(
            center.addObserver(forName: unlockPlayerOrientationNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.setLandscapeLock(enabled: false) }
            }
        )
        observers.append(
            center.addObserver(forName: preferPlayerPortraitNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.setPlayerPrefersPortrait(true) }
            }
        )
        observers.append(
            center.addObserver(forName: preferPlayerLandscapeNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.setPlayerPrefersPortrait(false) }
            }
        )
    }

    private func setLandscapeLock(enabled: Bool) {
        landscapeLockRequested = enabled
        if !enabled {
            parkedPlayerSuspension = false
            playerPrefersPortrait = false
        }
        applyLandscapeLock()
    }

    private func setPlayerPrefersPortrait(_ portrait: Bool) {
        guard playerPrefersPortrait != portrait else { return }
        playerPrefersPortrait = portrait
        applyLandscapeLock()
    }

    func setParkedPlayerSuspension(_ suspended: Bool) {
        guard parkedPlayerSuspension != suspended else { return }
        parkedPlayerSuspension = suspended
        applyLandscapeLock()
    }

    private func applyLandscapeLock() {
        let locked = landscapeLockRequested && !parkedPlayerSuspension
        let lockedOrientations: UIInterfaceOrientationMask = playerPrefersPortrait ? .portrait : .landscape
        let nextOrientations: UIInterfaceOrientationMask = locked ? lockedOrientations : .allButUpsideDown
        guard nextOrientations != supportedOrientations else { return }
        supportedOrientations = nextOrientations
        requestOrientationUpdate(for: nextOrientations, forceRotate: locked)
    }

    private func requestOrientationUpdate(for mask: UIInterfaceOrientationMask, forceRotate: Bool) {
        if #available(iOS 16.0, *) {
            let preferences = UIWindowScene.GeometryPreferences.iOS(interfaceOrientations: mask)
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .forEach { scene in
                    scene.requestGeometryUpdate(preferences) { error in
                        print("[OrientationLockCoordinator] Geometry update failed: \(error.localizedDescription)")
                    }
                }
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows)
                .forEach { window in
                    window.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
                    // The player dialog layer is sized while the window is landscape. Laying the
                    // window out again lets that layer follow the bounds Downloads is using now.
                    window.setNeedsLayout()
                    window.layoutIfNeeded()
                }
            // Locking rotates into the player. Unlocking is the return to the list. A sheet
            // whose frame is still the landscape window is dismissed there, not left off to the side.
            if !forceRotate {
                dismissSheetsThatDoNotFitTheWindow()
                DispatchQueue.main.async { [weak self] in
                    self?.dismissSheetsThatDoNotFitTheWindow()
                }
            }
        } else {
            if forceRotate {
                UIDevice.current.setValue(preferredLandscapeOrientation.rawValue, forKey: "orientation")
            } else if UIDevice.current.orientation.isPortrait {
                UIDevice.current.setValue(UIInterfaceOrientation.portrait.rawValue, forKey: "orientation")
            }
            UIViewController.attemptRotationToDeviceOrientation()
            if !forceRotate {
                dismissSheetsThatDoNotFitTheWindow()
            }
        }
    }

    private func dismissSheetsThatDoNotFitTheWindow() {
        let deviceIsLandscape = UIDevice.current.orientation == .landscapeLeft
            || UIDevice.current.orientation == .landscapeRight
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        for scene in scenes {
            for window in scene.windows {
                var misfits: [UIViewController] = []
                var controller = window.rootViewController
                while let presented = controller?.presentedViewController {
                    let frame = presented.view.convert(presented.view.bounds, to: window)
                    let decision = SeasonOverviewKt.overviewSheetFrameAfterReturn(
                        proposedLeft: Int32(frame.origin.x.rounded()),
                        proposedTop: Int32(frame.origin.y.rounded()),
                        proposedWidth: Int32(frame.size.width.rounded()),
                        proposedHeight: Int32(frame.size.height.rounded()),
                        windowWidth: Int32(window.bounds.width.rounded()),
                        windowHeight: Int32(window.bounds.height.rounded()),
                        deviceIsLandscape: deviceIsLandscape
                    )
                    if decision.isEmpty {
                        misfits.append(presented)
                    }
                    controller = presented
                }
                for presented in misfits.reversed() {
                    presented.view.transform = .identity
                    presented.dismiss(animated: false)
                }
            }
        }
    }

    private var preferredLandscapeOrientation: UIInterfaceOrientation {
        if let sceneOrientation = UIApplication.shared.connectedScenes
            .compactMap({ ($0 as? UIWindowScene)?.interfaceOrientation })
            .first(where: \.isLandscape) {
            return sceneOrientation
        }

        switch UIDevice.current.orientation {
        case .landscapeLeft:
            return .landscapeRight
        case .landscapeRight:
            return .landscapeLeft
        default:
            return .landscapeRight
        }
    }
}
