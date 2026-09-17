import XCTest
@testable import GlideCoreKit

final class NetworkTransportConfigTests: XCTestCase {
    func testDefaultConfigAdheresToSpecification() throws {
        let config = NetworkTransportConfig()

        XCTAssertEqual(config.port, 42424)
        XCTAssertEqual(config.tlsVersion, "TLSv1.3")
        XCTAssertTrue(config.isPeerToPeerEnabled)
        XCTAssertEqual(config.keepaliveIdle, 10)

        XCTAssertNoThrow(try config.validate())
    }

    func testInvalidTlsVersionThrows() {
        let invalidConfig = NetworkTransportConfig(tlsVersion: "TLSv1.2")
        XCTAssertThrowsError(try invalidConfig.validate()) { error in
            XCTAssertEqual(error as? NetworkTransportConfig.ConfigError, NetworkTransportConfig.ConfigError.invalidTlsVersion("TLSv1.2"))
        }
    }

    func testInvalidPortThrows() {
        let invalidConfig = NetworkTransportConfig(port: 0)
        XCTAssertThrowsError(try invalidConfig.validate()) { error in
            XCTAssertEqual(error as? NetworkTransportConfig.ConfigError, NetworkTransportConfig.ConfigError.invalidPort(0))
        }
    }

    func testPayloadCeilingsMatchProtocolLimits() {
        XCTAssertEqual(NetworkTransportConfig.maxControlPayloadBytes, 16384)
        XCTAssertEqual(NetworkTransportConfig.maxDataChunkBytes, 65536)
        XCTAssertEqual(NetworkTransportConfig.maxRecordPayloadBytes, 65536)
        XCTAssertEqual(NetworkTransportConfig.serviceType, "_glide._tcp")
        XCTAssertEqual(NetworkTransportConfig.serviceDomain, "local.")
    }
}
