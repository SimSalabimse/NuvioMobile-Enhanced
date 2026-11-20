import XCTest

final class BetaSmokeUITests: XCTestCase {
    func testAppLaunchesHomeRendersAndSettingsOpens() {
        let app = XCUIApplication()
        app.launch()
        passLaunchGates(app)

        let home = app.buttons["Home"]
        XCTAssertTrue(home.waitForExistence(timeout: 45), "Home did not render")

        let settings = app.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 15), "Settings tab was not on screen")
        settings.tap()

        let opened = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isSelected == true"),
            object: settings
        )
        XCTAssertEqual(XCTWaiter().wait(for: [opened], timeout: 15), .completed, "Settings did not open")
    }

    /// A fresh install shows the account screen, then an empty "Who's watching?" picker.
    /// Tap through both. Create a profile only when the picker has none to select.
    private func passLaunchGates(_ app: XCUIApplication) {
        let home = app.buttons["Home"]
        let guest = app.buttons["Continue Without Account"]
        if guest.waitForExistence(timeout: 25), !home.exists {
            guest.tap()
        }

        let picker = app.staticTexts["Who's watching?"]
        let pickerWithCurlyApostrophe = app.staticTexts["Who’s watching?"]
        let pickerShown = picker.waitForExistence(timeout: 25) || pickerWithCurlyApostrophe.exists
        if pickerShown, !home.exists {
            openAProfile(app)
        }
    }

    private func openAProfile(_ app: XCUIApplication) {
        let existing = app.buttons["Select profile"]
        if existing.waitForExistence(timeout: 8) {
            existing.tap()
            return
        }

        let addProfile = tappable(app, "Add Profile", timeout: 10)
        XCTAssertTrue(addProfile.exists, "Profile picker had no profile to open")
        addProfile.tap()

        let name = profileNameField(app)
        XCTAssertTrue(name.waitForExistence(timeout: 15), "Profile name field did not appear")
        name.tap()
        name.typeText("Guest")

        let create = tappable(app, "Create Profile", timeout: 10)
        XCTAssertTrue(create.exists, "Create Profile was not available")
        create.tap()

        let created = app.buttons["Select profile"]
        if created.waitForExistence(timeout: 20) {
            created.tap()
            return
        }
        let named = tappable(app, "Guest", timeout: 8)
        XCTAssertTrue(named.exists, "New profile did not appear")
        named.tap()
    }

    private func profileNameField(_ app: XCUIApplication) -> XCUIElement {
        let labeledField = app.textFields["Profile name"]
        if labeledField.waitForExistence(timeout: 8) {
            return labeledField
        }
        let labeledView = app.textViews["Profile name"]
        if labeledView.waitForExistence(timeout: 4) {
            return labeledView
        }
        if app.textFields.firstMatch.waitForExistence(timeout: 4) {
            return app.textFields.firstMatch
        }
        return app.textViews.firstMatch
    }

    private func tappable(_ app: XCUIApplication, _ label: String, timeout: TimeInterval) -> XCUIElement {
        let button = app.buttons[label]
        if button.waitForExistence(timeout: timeout) {
            return button
        }
        let text = app.staticTexts[label]
        if text.waitForExistence(timeout: 2) {
            return text
        }
        let image = app.images[label]
        if image.waitForExistence(timeout: 2) {
            return image
        }
        return app.descendants(matching: .any)[label]
    }
}
