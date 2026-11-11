import XCTest
@testable import Nuvio

final class SymbolMotionTests: XCTestCase {
    func testReduceMotionLeavesAStaticSymbol() {
        let request = NuvioSymbolRequest(style: .palette, repeating: .variableColor, discrete: .replace)
        let plan = NuvioSymbolMotion.plan(request, reduceMotion: true)

        XCTAssertEqual(plan.style, .palette)
        XCTAssertEqual(plan.repeating, .none)
        XCTAssertEqual(plan.discrete, .none)
        XCTAssertFalse(plan.hasEffect)
    }

    func testReduceMotionAlsoDropsBounceAndPulse() {
        let request = NuvioSymbolRequest(style: .hierarchical, repeating: .pulse, discrete: .bounce)
        let plan = NuvioSymbolMotion.plan(request, reduceMotion: true)

        XCTAssertEqual(plan.repeating, .none)
        XCTAssertEqual(plan.discrete, .none)
        XCTAssertEqual(plan.style, .hierarchical)
    }

    func testMotionKeepsTheRequestedEffect() {
        let request = NuvioSymbolRequest(style: .palette, repeating: .pulse, discrete: .bounce)
        let plan = NuvioSymbolMotion.plan(request, reduceMotion: false)

        XCTAssertEqual(
            plan,
            NuvioSymbolPlan(style: .palette, repeating: .pulse, discrete: .bounce)
        )
        XCTAssertTrue(plan.hasEffect)
        XCTAssertEqual(plan.style, .palette)
    }

    func testARunningDownloadUsesVariableColorAndReplace() {
        let running = NuvioSymbolMotion.download(downloading: true)
        let stopped = NuvioSymbolMotion.download(downloading: false)

        XCTAssertEqual(running.repeating, .variableColor)
        XCTAssertEqual(running.discrete, .replace)
        XCTAssertEqual(running.style, .hierarchical)
        XCTAssertEqual(stopped.repeating, .none)
        XCTAssertEqual(stopped.discrete, .replace)
    }

    func testStandByDownloadUsesPaletteWhileItRuns() {
        let running = NuvioSymbolMotion.standby(downloading: true)
        XCTAssertEqual(running.style, .palette)
        XCTAssertEqual(running.repeating, .variableColor)
        XCTAssertEqual(NuvioSymbolMotion.standby(downloading: false).repeating, .none)
    }

    func testAddBouncesOnce() {
        XCTAssertEqual(NuvioSymbolMotion.add.discrete, .bounce)
        XCTAssertEqual(NuvioSymbolMotion.add.repeating, .none)
        XCTAssertEqual(NuvioSymbolMotion.still.discrete, .none)
        XCTAssertEqual(NuvioSymbolMotion.still.repeating, .none)
    }
}
