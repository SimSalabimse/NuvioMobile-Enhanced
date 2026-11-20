import XCTest

final class BetaSmokeUITests: XCTestCase {
    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

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
    /// The guest button sits below the fold, so scroll until the tap can land.
    private func passLaunchGates(_ app: XCUIApplication) {
        let home = app.buttons["Home"]
        let guest = app.buttons["Continue Without Account"]
        if guest.waitForExistence(timeout: 25), !home.exists {
            var tries = 0
            while guest.exists, tries < 3 {
                tapOnScreen(app, guest)
                let gone = XCTNSPredicateExpectation(
                    predicate: NSPredicate(format: "exists == false"),
                    object: guest
                )
                if XCTWaiter().wait(for: [gone], timeout: 8) == .completed {
                    break
                }
                tries += 1
            }
        }

        let deadline = Date().addingTimeInterval(30)
        while Date() < deadline, !home.exists {
            if app.buttons["Select profile"].exists {
                tapOnScreen(app, app.buttons["Select profile"])
                return
            }
            let pickerTitle = labeled(app, "Who's watching?")
            let pickerTitleCurly = labeled(app, "Who’s watching?")
            let addProfile = labeled(app, "Add Profile")
            let profileName = app.textFields["Profile name"]
            if pickerTitle.exists || pickerTitleCurly.exists || addProfile.exists || profileName.exists {
                openAProfile(app)
                return
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.4))
        }
    }

    private func openAProfile(_ app: XCUIApplication) {
        let existing = app.buttons["Select profile"]
        if existing.waitForExistence(timeout: 8) {
            tapOnScreen(app, existing)
            return
        }
        createProfile(app)
    }

    private func createProfile(_ app: XCUIApplication) {
        let addProfile = labeled(app, "Add Profile")
        if addProfile.waitForExistence(timeout: 8) {
            tapOnScreen(app, addProfile)
        }

        let name = profileNameField(app, wait: 15)
        XCTAssertTrue(name.exists, "Profile name field did not appear")
        name.tap()
        name.typeText("Guest")

        let create = labeled(app, "Create Profile")
        XCTAssertTrue(create.waitForExistence(timeout: 10), "Create Profile was not available")
        tapOnScreen(app, create)

        let created = app.buttons["Select profile"]
        if created.waitForExistence(timeout: 20) {
            tapOnScreen(app, created)
            return
        }
        let named = labeled(app, "Guest")
        XCTAssertTrue(named.waitForExistence(timeout: 8), "New profile did not appear")
        tapOnScreen(app, named)
    }

    private func profileNameField(_ app: XCUIApplication, wait: TimeInterval) -> XCUIElement {
        let labeledField = app.textFields["Profile name"]
        if labeledField.waitForExistence(timeout: wait) {
            return labeledField
        }
        let labeledView = app.textViews["Profile name"]
        if labeledView.waitForExistence(timeout: min(wait, 3)) {
            return labeledView
        }
        if app.textFields.firstMatch.waitForExistence(timeout: min(wait, 3)) {
            return app.textFields.firstMatch
        }
        return app.textViews.firstMatch
    }

    private func labeled(_ app: XCUIApplication, _ label: String) -> XCUIElement {
        let button = app.buttons[label]
        if button.exists {
            return button
        }
        return app.descendants(matching: .any)[label]
    }

    /// Compose scroll views report an off-screen control with a hit point of {-1, -1}.
    /// Drag the screen until the control is hittable, then tap it.
    private func tapOnScreen(_ app: XCUIApplication, _ element: XCUIElement) {
        var attempts = 0
        while !element.isHittable, attempts < 8 {
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75))
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.30))
            start.press(forDuration: 0.05, thenDragTo: end)
            attempts += 1
        }
        if element.isHittable {
            element.tap()
            return
        }
        element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    }
}
