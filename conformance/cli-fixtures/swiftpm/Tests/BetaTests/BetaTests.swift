import Foundation
import XCTest
@testable import Beta

final class BetaTests: XCTestCase {
    func testBeta() throws {
        try FileManager.default.createDirectory(atPath: "markers", withIntermediateDirectories: true)
        FileManager.default.createFile(atPath: "markers/BetaTests.marker", contents: nil)
        XCTAssertFalse(FileManager.default.fileExists(atPath: "markers/BetaTests.fail"), "requested Swift fixture failure")
        XCTAssertEqual(beta(), 2)
    }
}
