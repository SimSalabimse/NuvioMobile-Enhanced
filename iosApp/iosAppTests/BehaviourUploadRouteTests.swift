import XCTest
@testable import Nuvio

final class BehaviourUploadRouteTests: XCTestCase {
    func testAMissingPathIsOfflineAndCellularIsNamed() {
        XCTAssertEqual(BehaviourUploadTask.decide(satisfied: false, wifi: false, cellular: false), .offline)
        XCTAssertEqual(BehaviourUploadTask.decide(satisfied: true, wifi: true, cellular: false), .wifi)
        XCTAssertEqual(BehaviourUploadTask.decide(satisfied: true, wifi: false, cellular: true), .cellular)
        XCTAssertEqual(BehaviourUploadTask.decide(satisfied: true, wifi: true, cellular: true), .wifi)
    }
}
