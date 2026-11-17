import Foundation

enum DiagnosticsProcessSignal: Equatable {
    case launch
    case becameActive
    case enteredBackground
    case willTerminate
}

struct DiagnosticsCrashMarkerDecision: Equatable {
    /// True only when a previous process left the marker set and this signal is a new launch.
    var logUncleanTermination: Bool
    var markerSet: Bool
}

enum DiagnosticsCrashMarker {
    static let storageKey = "NuvioDiagnosticsCrashMarker"

    /// Launch arms the marker. Background and terminate clear it, so a normal background is not a
    /// crash. Becoming active arms it again and does not log: the crash line is only for the next
    /// cold launch.
    static func decisionAfter(
        markerWasSet: Bool,
        signal: DiagnosticsProcessSignal
    ) -> DiagnosticsCrashMarkerDecision {
        switch signal {
        case .launch:
            return DiagnosticsCrashMarkerDecision(
                logUncleanTermination: markerWasSet,
                markerSet: true
            )
        case .becameActive:
            return DiagnosticsCrashMarkerDecision(
                logUncleanTermination: false,
                markerSet: true
            )
        case .enteredBackground, .willTerminate:
            return DiagnosticsCrashMarkerDecision(
                logUncleanTermination: false,
                markerSet: false
            )
        }
    }

    /// Same as launch, except a process that is already in the background does not leave the
    /// marker set. Launch-time `.inactive` still leaves it set, so a crash during startup counts.
    static func markerAfterLaunch(
        markerWasSet: Bool,
        alreadyInBackground: Bool
    ) -> DiagnosticsCrashMarkerDecision {
        let launch = decisionAfter(markerWasSet: markerWasSet, signal: .launch)
        guard alreadyInBackground else { return launch }
        let backgrounded = decisionAfter(markerWasSet: launch.markerSet, signal: .enteredBackground)
        return DiagnosticsCrashMarkerDecision(
            logUncleanTermination: launch.logUncleanTermination,
            markerSet: backgrounded.markerSet
        )
    }
}
