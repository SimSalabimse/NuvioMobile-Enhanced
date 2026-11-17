import XCTest
@testable import iosApp

final class DiagnosticsFileWriteTests: XCTestCase {
    private let lowSpaceLimit = DiagnosticsFileWriteGate.lowSpaceThresholdBytes

    func testThrowingWriterDoesNotCrashAndPausesUntilBackoff() {
        let appender = ScriptedDiagnosticsAppender()
        appender.error = ScriptedDiagnosticsAppender.FullDisk()
        var now = Date(timeIntervalSince1970: 1_700_000_000)
        var freeBytes: Int64? = lowSpaceLimit
        let fileLogWriter = DiagnosticsFileLogWriter(
            appender: appender,
            now: { now },
            freeBytes: { freeBytes }
        )

        XCTAssertFalse(fileLogWriter.append(line: "download failed"))
        XCTAssertEqual(appender.lines, [])
        XCTAssertEqual(appender.attempts, 1)

        XCTAssertFalse(fileLogWriter.append(line: "still full"))
        XCTAssertEqual(appender.attempts, 1)

        now = now.addingTimeInterval(DiagnosticsFileWriteGate.failureBackoff - 1)
        XCTAssertFalse(fileLogWriter.append(line: "still paused"))
        XCTAssertEqual(appender.attempts, 1)

        now = now.addingTimeInterval(1)
        appender.error = nil
        XCTAssertTrue(fileLogWriter.append(line: "retry"))
        XCTAssertEqual(appender.attempts, 2)
        XCTAssertEqual(appender.lines, ["retry"])

        XCTAssertTrue(fileLogWriter.append(line: "after backoff"))
        XCTAssertEqual(appender.attempts, 3)
        XCTAssertEqual(appender.lines, ["retry", "after backoff"])
    }

    func testLowSpaceSkipsWriteUntilCapacityReturns() {
        let appender = ScriptedDiagnosticsAppender()
        var freeBytes: Int64? = lowSpaceLimit - 1
        let fileLogWriter = DiagnosticsFileLogWriter(
            appender: appender,
            now: { Date(timeIntervalSince1970: 0) },
            freeBytes: { freeBytes }
        )

        XCTAssertFalse(fileLogWriter.append(line: "skipped"))
        XCTAssertEqual(appender.attempts, 0)

        freeBytes = lowSpaceLimit
        XCTAssertTrue(fileLogWriter.append(line: "written"))
        XCTAssertEqual(appender.lines, ["written"])
    }

    func testUnknownFreeSpaceStillAttemptsTheWrite() {
        let appender = ScriptedDiagnosticsAppender()
        let fileLogWriter = DiagnosticsFileLogWriter(
            appender: appender,
            now: { Date(timeIntervalSince1970: 0) },
            freeBytes: { nil }
        )

        XCTAssertTrue(fileLogWriter.append(line: "attempted"))
        XCTAssertEqual(appender.attempts, 1)
    }

    func testGateDecisionTable() {
        var writeGate = DiagnosticsFileWriteGate()
        let now = Date(timeIntervalSince1970: 10_000)

        XCTAssertEqual(writeGate.decision(freeBytes: nil, now: now), .write)
        XCTAssertEqual(writeGate.decision(freeBytes: lowSpaceLimit, now: now), .write)
        XCTAssertEqual(writeGate.decision(freeBytes: lowSpaceLimit - 1, now: now), .skipLowSpace)
        XCTAssertEqual(writeGate.decision(freeBytes: 0, now: now), .skipLowSpace)

        writeGate.noteWriteFailed(now: now)
        XCTAssertEqual(writeGate.decision(freeBytes: lowSpaceLimit, now: now), .skipPaused)
        XCTAssertEqual(
            writeGate.decision(freeBytes: lowSpaceLimit, now: now.addingTimeInterval(DiagnosticsFileWriteGate.failureBackoff)),
            .write
        )

        writeGate.noteWriteSucceeded()
        XCTAssertEqual(writeGate.decision(freeBytes: 0, now: now), .skipLowSpace)
    }

    func testCrashMarkerClearsOnBackgroundAndTerminateWithoutLogging() {
        let armed = DiagnosticsCrashMarker.markerAfterLaunch(markerWasSet: false, alreadyInBackground: false)
        XCTAssertFalse(armed.logUncleanTermination)
        XCTAssertTrue(armed.markerSet)

        let previousCrash = DiagnosticsCrashMarker.markerAfterLaunch(markerWasSet: true, alreadyInBackground: false)
        XCTAssertTrue(previousCrash.logUncleanTermination)
        XCTAssertTrue(previousCrash.markerSet)

        let alreadyBackground = DiagnosticsCrashMarker.markerAfterLaunch(markerWasSet: true, alreadyInBackground: true)
        XCTAssertTrue(alreadyBackground.logUncleanTermination)
        XCTAssertFalse(alreadyBackground.markerSet)

        let background = DiagnosticsCrashMarker.decisionAfter(markerWasSet: true, signal: .enteredBackground)
        XCTAssertFalse(background.logUncleanTermination)
        XCTAssertFalse(background.markerSet)

        let terminateSignal = DiagnosticsCrashMarker.decisionAfter(markerWasSet: true, signal: .willTerminate)
        XCTAssertFalse(terminateSignal.logUncleanTermination)
        XCTAssertFalse(terminateSignal.markerSet)

        let activeSignal = DiagnosticsCrashMarker.decisionAfter(markerWasSet: false, signal: .becameActive)
        XCTAssertFalse(activeSignal.logUncleanTermination)
        XCTAssertTrue(activeSignal.markerSet)

        let launchAfterBackground = DiagnosticsCrashMarker.decisionAfter(markerWasSet: background.markerSet, signal: .launch)
        XCTAssertFalse(launchAfterBackground.logUncleanTermination)
        XCTAssertTrue(launchAfterBackground.markerSet)
    }
}

private final class ScriptedDiagnosticsAppender: DiagnosticsFileAppending {
    struct FullDisk: Error {}

    var lines: [String] = []
    var attempts = 0
    var error: Error?

    func append(line: String) throws {
        attempts += 1
        if let error {
            throw error
        }
        lines.append(line)
    }
}
