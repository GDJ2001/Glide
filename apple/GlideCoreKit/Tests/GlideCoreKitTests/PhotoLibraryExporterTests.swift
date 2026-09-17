import XCTest
@testable import GlideCoreKit

final class PhotoLibraryExporterTests: XCTestCase {
    func testClassifyImages() {
        XCTAssertEqual(PhotoLibraryExporter.classify(filename: "photo.jpg"), .image)
        XCTAssertEqual(PhotoLibraryExporter.classify(filename: "image.HEIC"), .image)
        XCTAssertEqual(PhotoLibraryExporter.classify(filename: "graphic.PNG"), .image)
        XCTAssertEqual(PhotoLibraryExporter.classify(filename: "animation.gif"), .image)
    }

    func testClassifyVideos() {
        XCTAssertEqual(PhotoLibraryExporter.classify(filename: "movie.mp4"), .video)
        XCTAssertEqual(PhotoLibraryExporter.classify(filename: "recording.MOV"), .video)
        XCTAssertEqual(PhotoLibraryExporter.classify(filename: "clip.m4v"), .video)
    }

    func testClassifyNonMedia() {
        XCTAssertEqual(PhotoLibraryExporter.classify(filename: "document.pdf"), .nonMedia)
        XCTAssertEqual(PhotoLibraryExporter.classify(filename: "archive.zip"), .nonMedia)
        XCTAssertEqual(PhotoLibraryExporter.classify(filename: "data.json"), .nonMedia)
        XCTAssertEqual(PhotoLibraryExporter.classify(filename: "source.swift"), .nonMedia)
    }

    func testIsMediaFile() {
        XCTAssertTrue(PhotoLibraryExporter.isMediaFile(filename: "pic.jpeg"))
        XCTAssertTrue(PhotoLibraryExporter.isMediaFile(filename: "vid.mov"))
        XCTAssertFalse(PhotoLibraryExporter.isMediaFile(filename: "text.txt"))
    }
}
