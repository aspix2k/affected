import Foundation
import XCTest

final class AppTests: XCTestCase {
    func testAffectedExecution() throws {
        let source = URL(fileURLWithPath: #filePath)
        let marker = source.deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("affected-xcode-test.marker")
        try "executed".write(to: marker, atomically: true, encoding: .utf8)
    }
}
