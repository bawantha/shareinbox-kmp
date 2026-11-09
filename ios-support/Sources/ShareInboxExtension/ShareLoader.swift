import Foundation
import UniformTypeIdentifiers

public struct LoadedAttachment {
    public let url: URL
    public let name: String
    public let contentType: String?
}

public struct LoadedShare {
    public let text: String?
    public let url: String?
    public let files: [LoadedAttachment]
    public let temporaryDirectory: URL
    public func cleanup() { try? FileManager.default.removeItem(at: temporaryDirectory) }
}

public enum ShareLoadError: Error { case empty, unsupportedType, tooLarge, tooManyItems, invalidRepresentation }

public final class ShareLoader {
    private let maxBytes: Int64
    private let maxFileBytes: Int64
    private let maxItems: Int
    private let temporaryRoot: URL
    public init(maxBytes: Int64 = 100 * 1024 * 1024, maxFileBytes: Int64 = 50 * 1024 * 1024, maxItems: Int = 20, temporaryRoot: URL = FileManager.default.temporaryDirectory) {
        precondition(maxBytes > 0 && maxFileBytes > 0 && maxItems > 0)
        self.maxBytes = maxBytes
        self.maxFileBytes = maxFileBytes
        self.maxItems = maxItems
        self.temporaryRoot = temporaryRoot
    }

    /// Copies file representations BEFORE the provider callback returns. Keep the result
    /// alive until the Kotlin inbox commits it, then call cleanup().
    public func load(_ providers: [NSItemProvider]) async throws -> LoadedShare {
        guard !providers.isEmpty else { throw ShareLoadError.empty }
        guard providers.count <= maxItems else { throw ShareLoadError.tooManyItems }
        let directory = temporaryRoot.appendingPathComponent("shareinbox-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        do {
            var texts: [String] = []
            var urls: [String] = []
            var files: [LoadedAttachment] = []
            var total: Int64 = 0
            for (index, provider) in providers.enumerated() {
                try Task.checkCancellation()
                if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                    let destination = directory.appendingPathComponent("attachment-\(index).bin")
                    let copied = try await copyFileURL(provider, destination: destination, limit: min(maxFileBytes, maxBytes - total))
                    total += copied.0
                    files.append(LoadedAttachment(url: destination, name: provider.suggestedName ?? copied.1.lastPathComponent,
                                                  contentType: UTType(filenameExtension: copied.1.pathExtension)?.preferredMIMEType))
                } else if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier)
                    && !provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                    let item = try await loadItem(provider, type: UTType.url.identifier)
                    let value = Self.decodeURL(item)
                    guard let url = value, url.scheme != nil, !url.isFileURL else { throw ShareLoadError.invalidRepresentation }
                    urls.append(url.absoluteString)
                } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier)
                            && !provider.hasItemConformingToTypeIdentifier(UTType.pdf.identifier)
                            && !provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                    let item = try await loadItem(provider, type: UTType.plainText.identifier)
                    if let string = item as? String { texts.append(string) }
                    else if let data = item as? Data, let string = String(data: data, encoding: .utf8) { texts.append(string) }
                    else { throw ShareLoadError.invalidRepresentation }
                } else {
                    guard let type = provider.registeredTypeIdentifiers.first(where: {
                        UTType($0)?.conforms(to: .data) == true && $0 != UTType.fileURL.identifier
                    }) else { throw ShareLoadError.unsupportedType }
                    let destination = directory.appendingPathComponent("attachment-\(index).bin")
                    let size = try await copyRepresentation(provider, type: type, destination: destination,
                                                            limit: min(maxFileBytes, maxBytes - total))
                    total += size
                    files.append(LoadedAttachment(url: destination, name: provider.suggestedName ?? "attachment",
                                                  contentType: UTType(type)?.preferredMIMEType))
                }
                if texts.joined(separator: "\n").utf16.count + urls.joined(separator: "\n").utf16.count > 100_000 {
                    throw ShareLoadError.tooLarge
                }
            }
            try Task.checkCancellation()
            return LoadedShare(text: texts.isEmpty ? nil : texts.joined(separator: "\n"),
                               url: urls.isEmpty ? nil : urls.joined(separator: "\n"), files: files, temporaryDirectory: directory)
        } catch {
            try? FileManager.default.removeItem(at: directory)
            throw error
        }
    }

    private func loadItem(_ provider: NSItemProvider, type: String) async throws -> NSSecureCoding {
        try await withCheckedThrowingContinuation { continuation in
            provider.loadItem(forTypeIdentifier: type, options: nil) { item, error in
                if let error { continuation.resume(throwing: error) }
                else if let item { continuation.resume(returning: item) }
                else { continuation.resume(throwing: ShareLoadError.invalidRepresentation) }
            }
        }
    }

    private func copyRepresentation(_ provider: NSItemProvider, type: String, destination: URL, limit: Int64) async throws -> Int64 {
        try await withCheckedThrowingContinuation { continuation in
            provider.loadFileRepresentation(forTypeIdentifier: type) { url, error in
                do {
                    if let error { throw error }
                    guard let url else { throw ShareLoadError.invalidRepresentation }
                    // The borrowed URL may be deleted as soon as this callback returns.
                    let size = try Self.copyFile(url, to: destination, limit: limit)
                    continuation.resume(returning: size)
                } catch { continuation.resume(throwing: error) }
            }
        }
    }

    private func copyFileURL(_ provider: NSItemProvider, destination: URL, limit: Int64) async throws -> (Int64, URL) {
        try await withCheckedThrowingContinuation { continuation in
            provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, error in
                do {
                    if let error { throw error }
                    guard let item, let url = Self.decodeURL(item), url.isFileURL else { throw ShareLoadError.invalidRepresentation }
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    let size = try Self.copyFile(url, to: destination, limit: limit)
                    continuation.resume(returning: (size, url))
                } catch { continuation.resume(throwing: error) }
            }
        }
    }

    private static func decodeURL(_ item: NSSecureCoding) -> URL? {
        if let url = item as? URL { return url }
        if let string = item as? String { return URL(string: string) }
        if let data = item as? Data, let string = String(data: data, encoding: .utf8) { return URL(string: string) }
        return nil
    }

    private static func copyFile(_ url: URL, to destination: URL, limit: Int64) throws -> Int64 {
        guard try url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true else { throw ShareLoadError.invalidRepresentation }
        let source = try FileHandle(forReadingFrom: url)
        defer { try? source.close() }
        guard FileManager.default.createFile(atPath: destination.path, contents: nil) else { throw CocoaError(.fileWriteUnknown) }
        let target = try FileHandle(forWritingTo: destination)
        defer { try? target.close() }
        var size: Int64 = 0
        while let data = try source.read(upToCount: 65536), !data.isEmpty {
            guard Int64(data.count) <= limit - size else { throw ShareLoadError.tooLarge }
            try target.write(contentsOf: data)
            size += Int64(data.count)
        }
        return size
    }
}
