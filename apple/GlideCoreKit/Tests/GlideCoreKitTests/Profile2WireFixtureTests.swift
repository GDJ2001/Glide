import Foundation
import XCTest
@testable import GlideCoreKit

final class Profile2WireFixtureTests: XCTestCase {
    private func fixtureURL() throws -> URL {
        var folder = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = folder.appendingPathComponent("doc/protocol/fixtures/profile2-fixtures.json")
            if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            folder.deleteLastPathComponent()
        }
        throw NSError(domain: "GlideTests", code: 1)
    }

    func testSharedProfile2WireFramesAndStrictFields() throws {
        let source = try Data(contentsOf: fixtureURL())
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: source) as? [String: Any])
        let fixtures = try XCTUnwrap(root["profile2WireFrames"] as? [[String: Any]])
        XCTAssertEqual(fixtures.count, 8)
        for fixture in fixtures {
            let name = try XCTUnwrap(fixture["name"] as? String)
            let hex = try XCTUnwrap(fixture["frameHex"] as? String)
            let bytes = try stride(from: 0, to: hex.count, by: 2).map { offset -> UInt8 in
                let start = hex.index(hex.startIndex, offsetBy: offset)
                let end = hex.index(start, offsetBy: 2)
                return try XCTUnwrap(UInt8(hex[start..<end], radix: 16))
            }
            let frame = Data(bytes)
            XCTAssertGreaterThanOrEqual(frame.count, 5, name)
            let declared = frame.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
            XCTAssertEqual(Int(declared), frame.count - 4, name)
            let payload = Data(frame.dropFirst(4))
            try Profile2WireValidator.validate(payload)
            let envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: payload) as? [String: Any])
            let expected = try XCTUnwrap(fixture["json"] as? [String: Any])
            XCTAssertEqual(envelope["type"] as? String, expected["type"] as? String, name)
        }
        let id = "770e8400-e29b-41d4-a716-446655440002"
        let unknown = "{\"v\":1,\"type\":\"PAIR_RESULT\",\"sessionId\":\"\(id)\",\"paired\":true,\"extra\":1}"
        let duplicate = "{\"v\":1,\"type\":\"PAIR_RESULT\",\"sessionId\":\"\(id)\",\"paired\":true,\"paired\":false}"
        XCTAssertThrowsError(try Profile2WireValidator.validate(Data(unknown.utf8)))
        XCTAssertThrowsError(try Profile2WireValidator.validate(Data(duplicate.utf8)))
    }
}
