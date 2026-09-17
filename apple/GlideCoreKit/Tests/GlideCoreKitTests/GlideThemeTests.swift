import XCTest
@testable import GlideCoreKit

final class GlideThemeTests: XCTestCase {
    func testBrandColorConstants() {
        XCTAssertEqual(GlideTheme.brandBlueHex, "#0D6EFD")
        XCTAssertEqual(GlideTheme.brandSuccessHex, "#198754")
        XCTAssertEqual(GlideTheme.brandWarningHex, "#FFC107")
        XCTAssertEqual(GlideTheme.brandDangerHex, "#DC3545")
    }

    func testHexToRgbParsing() {
        let blue = GlideTheme.hexToRgb("#0D6EFD")
        XCTAssertNotNull(blue)
        XCTAssertEqual(blue!.red, 13.0 / 255.0, accuracy: 0.001)
        XCTAssertEqual(blue!.green, 110.0 / 255.0, accuracy: 0.001)
        XCTAssertEqual(blue!.blue, 253.0 / 255.0, accuracy: 0.001)

        // Without leading '#'
        let green = GlideTheme.hexToRgb("198754")
        XCTAssertNotNull(green)
        XCTAssertEqual(green!.red, 25.0 / 255.0, accuracy: 0.001)

        // Invalid hex string
        XCTAssertNil(GlideTheme.hexToRgb("invalid"))
        XCTAssertNil(GlideTheme.hexToRgb("#12345"))
    }

    func testDesignTokenSpacingAndRadius() {
        XCTAssertEqual(GlideTheme.cardCornerRadius, 16.0)
        XCTAssertEqual(GlideTheme.buttonCornerRadius, 12.0)
        XCTAssertEqual(GlideTheme.tagCornerRadius, 8.0)

        XCTAssertEqual(GlideTheme.spacingXs, 4.0)
        XCTAssertEqual(GlideTheme.spacingSm, 8.0)
        XCTAssertEqual(GlideTheme.spacingMd, 16.0)
        XCTAssertEqual(GlideTheme.spacingLg, 24.0)
        XCTAssertEqual(GlideTheme.spacingXl, 32.0)
    }
}
