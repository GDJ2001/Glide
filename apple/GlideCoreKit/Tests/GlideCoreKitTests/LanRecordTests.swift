import XCTest
@testable import GlideCoreKit

final class LanRecordTests: XCTestCase {
    func testBootstrapRecordRoundTrip() throws {
        let json = "{\"hello\":\"glide\"}".data(using: .utf8)!
        let record = LanRecord.bootstrap(jsonBytes: json)

        let frame = try LanRecordCodec.encode(record)
        XCTAssertEqual(frame[0], LanRecordKind.bootstrap.rawValue)
        XCTAssertEqual(frame.count, 5 + json.count)

        let decoded = try LanRecordCodec.decode(frame)
        XCTAssertEqual(decoded, record)
    }

    func testDataRecordRoundTrip() throws {
        let uuid = UUID(uuidString: "8a32a67e-071a-4d23-92b4-3ec9fef39db3")!
        let chunk = "Binary Payload 12345".data(using: .utf8)!
        let record = LanRecord.data(bulkRef: uuid, chunk: chunk)

        let frame = try LanRecordCodec.encode(record)
        XCTAssertEqual(frame[0], LanRecordKind.data.rawValue)
        XCTAssertEqual(frame.count, 5 + 16 + chunk.count)

        let decoded = try LanRecordCodec.decode(frame)
        XCTAssertEqual(decoded, record)
    }

    func testStreamEndRecordRoundTrip() throws {
        let uuid = UUID(uuidString: "8a32a67e-071a-4d23-92b4-3ec9fef39db3")!
        let actualBytes: UInt64 = 104_857_600 // 100 MB
        let record = LanRecord.streamEnd(bulkRef: uuid, actualBytes: actualBytes)

        let frame = try LanRecordCodec.encode(record)
        XCTAssertEqual(frame[0], LanRecordKind.streamEnd.rawValue)
        XCTAssertEqual(frame.count, 5 + 24)

        let decoded = try LanRecordCodec.decode(frame)
        XCTAssertEqual(decoded, record)
    }

    func testDecodeHeaderTooShortThrows() {
        let shortFrame = Data([0x01, 0x00, 0x00])
        XCTAssertThrowsError(try LanRecordCodec.decode(shortFrame)) { error in
            XCTAssertEqual(error as? LanRecordError, LanRecordError.headerTooShort)
        }
    }

    func testDecodeUnknownKindThrows() {
        let badFrame = Data([0x99, 0x00, 0x00, 0x00, 0x01, 0x42])
        XCTAssertThrowsError(try LanRecordCodec.decode(badFrame)) { error in
            XCTAssertEqual(error as? LanRecordError, LanRecordError.unknownRecordKind(0x99))
        }
    }

    func testDecodeRejectsTrailingBytes() throws {
        var frame = try LanRecordCodec.encode(.control(payload: Data("x".utf8)))
        frame.append(0)
        XCTAssertThrowsError(try LanRecordCodec.decode(frame)) { error in
            XCTAssertEqual(error as? LanRecordError,
                           .trailingPayload(expected: 6, actual: 7))
        }
    }

    func testDecodeStreamEndFromSlicedData() throws {
        let record = LanRecord.streamEnd(
            bulkRef: UUID(uuidString: "8a32a67e-071a-4d23-92b4-3ec9fef39db3")!,
            actualBytes: 1_234_567)
        let frame = try LanRecordCodec.encode(record)
        let padded = Data([0xff]) + frame
        XCTAssertEqual(try LanRecordCodec.decode(padded.dropFirst()), record)
    }
}
