import Foundation
import XCTest
@testable import Alpha

final class AlphaTests: XCTestCase {
    func testAlpha() throws {
        try FileManager.default.createDirectory(atPath: "markers", withIntermediateDirectories: true)
        FileManager.default.createFile(atPath: "markers/AlphaTests.marker", contents: nil)
        XCTAssertFalse(FileManager.default.fileExists(atPath: "markers/AlphaTests.fail"), "requested Swift fixture failure")
        if FileManager.default.fileExists(atPath: "markers/AlphaTests.hang") {
            let child = Process()
            child.executableURL = URL(fileURLWithPath: "/bin/sleep")
            child.arguments = ["300"]
            try child.run()
            let pids = "\(ProcessInfo.processInfo.processIdentifier)\n\(child.processIdentifier)\n"
            try pids.write(toFile: "markers/hang.pids", atomically: true, encoding: .utf8)
            Thread.sleep(forTimeInterval: 300)
        }
        XCTAssertEqual(alpha(), 1)
    }
}
