import XCTest

final class BetaSmokeUITests: XCTestCase {
    func testAppLaunchesHomeRendersAndSettingsOpens() {
        let app = XCUIApplication()
        app.launch()

        let home = app.buttons["Home"]
        XCTAssertTrue(home.waitForExistence(timeout: 45), "Home did not render")

        let settings = app.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 10), "Settings tab was not on screen")
        settings.tap()

        let opened = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isSelected == true"),
            object: settings
        )
        XCTAssertEqual(XCTWaiter().wait(for: [opened], timeout: 10), .completed, "Settings did not open")
    }
}
