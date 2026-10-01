import Foundation
import XCTest
@testable import Gamma

final class GammaTests: XCTestCase {
    func testGamma() throws {
        try FileManager.default.createDirectory(atPath: "markers", withIntermediateDirectories: true)
        FileManager.default.createFile(atPath: "markers/GammaTests.marker", contents: nil)
        XCTAssertFalse(FileManager.default.fileExists(atPath: "markers/GammaTests.fail"), "requested Swift fixture failure")
        XCTAssertEqual(gamma(), 3)
    }
}
