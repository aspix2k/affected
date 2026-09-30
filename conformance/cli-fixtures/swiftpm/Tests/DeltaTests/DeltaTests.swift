import Foundation
import Testing
@testable import Delta

@Test func freeDelta() throws {
    try FileManager.default.createDirectory(atPath: "markers", withIntermediateDirectories: true)
    FileManager.default.createFile(atPath: "markers/DeltaTests.marker", contents: nil)
    #expect(!FileManager.default.fileExists(atPath: "markers/DeltaTests.fail"))
    #expect(delta() == 4)
}

@Suite struct DeltaSuite {
    @Test func inSuite() {
        #expect(delta() == 4)
    }
}
