import XCTest
@testable import Nuvio

final class PlayerSurfaceThreadingTests: XCTestCase {
    func testDrawableSizeFromABackgroundQueueCommitsBeforeReturning() {
        let layer = MetalLayer()
        layer.delegate = nil
        let done = expectation(description: "size")
        DispatchQueue.global(qos: .userInitiated).async {
            layer.drawableSize = CGSize(width: 32, height: 32)
            XCTAssertFalse(Thread.isMainThread)
            XCTAssertEqual(layer.drawableSize.width, 32)
            XCTAssertEqual(layer.drawableSize.height, 32)
            XCTAssertNil(layer.superlayer)
            XCTAssertFalse(layer.delegate is UIView)
            done.fulfill()
        }
        wait(for: [done], timeout: 2)
    }

    func testViewMutationIsAllowedOnTheMainThread() {
        let done = expectation(description: "main")
        DispatchQueue.main.async {
            PlayerSurfaceThreading.assertMainThread()
            XCTAssertTrue(PlayerSurfaceThreading.isMainThread)
            done.fulfill()
        }
        wait(for: [done], timeout: 2)
    }
}
