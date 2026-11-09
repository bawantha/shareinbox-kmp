import XCTest
import UniformTypeIdentifiers
@testable import ShareInboxExtension

final class ShareLoaderTests: XCTestCase {
    private var root: URL!
    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws { try FileManager.default.removeItem(at: root) }

    private func text(_ value: String) -> NSItemProvider {
        NSItemProvider(item: value as NSString, typeIdentifier: UTType.plainText.identifier)
    }
    private func pdf(_ content: String, name: String = "report.pdf") throws -> NSItemProvider {
        let file = root.appendingPathComponent(UUID().uuidString)
        try Data(content.utf8).write(to: file)
        let provider = NSItemProvider()
        provider.suggestedName = name
        provider.registerFileRepresentation(forTypeIdentifier: UTType.pdf.identifier, fileOptions: [], visibility: .all) { completion in
            completion(file, false, nil)
            return nil
        }
        return provider
    }
    private var staging: URL { root.appendingPathComponent("staging") }

    func testLoadsText() async throws {
        let result = try await ShareLoader(temporaryRoot: staging).load([text("hello")])
        defer { result.cleanup() }
        XCTAssertEqual(result.text, "hello")
        XCTAssertTrue(result.files.isEmpty)
    }
    func testLoadsURL() async throws {
        let provider = NSItemProvider(item: NSURL(string: "https://example.com")!, typeIdentifier: UTType.url.identifier)
        let result = try await ShareLoader(temporaryRoot: staging).load([provider])
        defer { result.cleanup() }
        XCTAssertEqual(result.url, "https://example.com")
    }
    func testCopiesBorrowedFilesAndKeepsDuplicateNamesSeparate() async throws {
        let result = try await ShareLoader(temporaryRoot: staging).load([pdf("one"), pdf("two")])
        XCTAssertEqual(result.files.map(\.name), ["report.pdf", "report.pdf"])
        XCTAssertEqual(try result.files.map { try String(contentsOf: $0.url, encoding: .utf8) }, ["one", "two"])
        XCTAssertNotEqual(result.files[0].url, result.files[1].url)
        XCTAssertEqual(result.files[0].contentType, "application/pdf")
        result.cleanup()
        XCTAssertFalse(FileManager.default.fileExists(atPath: result.temporaryDirectory.path))
    }
    func testRejectsOversizedFileAndCleansPartialCopies() async throws {
        do {
            _ = try await ShareLoader(maxFileBytes: 2, temporaryRoot: staging).load([pdf("123")])
            XCTFail("Expected size rejection")
        } catch ShareLoadError.tooLarge {} catch { XCTFail("Unexpected error: \(error)") }
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: staging.path), [])
    }
    func testTotalLimitSpansAttachments() async throws {
        do {
            _ = try await ShareLoader(maxBytes: 3, temporaryRoot: staging).load([pdf("12"), pdf("34")])
            XCTFail("Expected total-size rejection")
        } catch ShareLoadError.tooLarge {} catch { XCTFail("Unexpected error: \(error)") }
    }
    func testExactLimitAccepted() async throws {
        let result = try await ShareLoader(maxBytes: 3, maxFileBytes: 3, temporaryRoot: staging).load([pdf("123")])
        result.cleanup()
    }
    func testRejectsEmptyInput() async throws {
        do {
            _ = try await ShareLoader(temporaryRoot: staging).load([])
            XCTFail("Expected empty rejection")
        } catch ShareLoadError.empty {} catch { XCTFail("Unexpected error: \(error)") }
    }
    func testRejectsExcessiveItems() async throws {
        do {
            _ = try await ShareLoader(maxItems: 1, temporaryRoot: staging).load([text("one"), text("two")])
            XCTFail("Expected count rejection")
        } catch ShareLoadError.tooManyItems {} catch { XCTFail("Unexpected error: \(error)") }
    }

    func testImportsFileURLRepresentation() async throws {
        let file = root.appendingPathComponent("invoice.pdf")
        try Data("invoice".utf8).write(to: file)
        let provider = NSItemProvider(item: file as NSURL, typeIdentifier: UTType.fileURL.identifier)
        provider.suggestedName = "invoice.pdf"
        let result = try await ShareLoader(temporaryRoot: staging).load([provider])
        defer { result.cleanup() }
        XCTAssertEqual(result.files.count, 1)
        XCTAssertEqual(try String(contentsOf: result.files[0].url, encoding: .utf8), "invoice")
    }

    func testFailureAfterFirstFileCleansWholeDelivery() async throws {
        let broken = NSItemProvider()
        do {
            _ = try await ShareLoader(temporaryRoot: staging).load([pdf("good"), broken])
            XCTFail("Expected unsupported provider failure")
        } catch ShareLoadError.unsupportedType {} catch { XCTFail("Unexpected error: \(error)") }
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: staging.path), [])
    }
}
