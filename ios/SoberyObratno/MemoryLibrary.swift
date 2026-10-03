import Foundation
import UIKit
import SwiftUI
import CoreImage

@MainActor final class MemoryLibrary: ObservableObject {
    @Published private(set) var sessions: [MemorySession] = []
    @Published var syncing = false
    @Published var message = ""
    @Published private(set) var pendingDeletionIDs = Set<String>()
    private var deletedSessionIDs = Set<String>()

    private let root: URL
    private let index: URL
    private let transport: URLSession
    private let imageCache = NSCache<NSString, UIImage>()
    private let imageContext = CIContext()

    init(storageRoot: URL? = nil, transport: URLSession = .shared) {
        self.transport = transport
        root = storageRoot ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("SoberyObratno", isDirectory: true)
        index = root.appendingPathComponent("library.json")
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        if let data = try? Data(contentsOf: index),
           let manifest = try? JSONDecoder().decode(MemoryManifest.self, from: data) {
            deletedSessionIDs = manifest.deletedSessionIDs ?? []
            pendingDeletionIDs = manifest.pendingDeletionIDs ?? []
            sessions = manifest.sessions.filter { !deletedSessionIDs.contains($0.id) }
        }
    }

    func photo(_ step: MemoryStep, enhanced: Bool = true) -> UIImage? {
        let cacheKey = "\(step.id)-\(enhanced)" as NSString
        if let cached = imageCache.object(forKey: cacheKey) { return cached }
        let url = root.appendingPathComponent(step.photo)
        guard let data = try? Data(contentsOf: url), let original = UIImage(data: data) else { return nil }
        let result = enhanced ? brightenIfNeeded(original) : original
        imageCache.setObject(result, forKey: cacheKey)
        return result
    }

    private func brightenIfNeeded(_ original: UIImage) -> UIImage {
        guard let input = CIImage(image: original),
              input.extent.width > 0, input.extent.height > 0 else { return original }
        let scale = min(128.0 / input.extent.width, 128.0 / input.extent.height)
        let sample = input.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let bounds = sample.extent.integral
        let width = Int(bounds.width)
        let height = Int(bounds.height)
        guard width > 0, height > 0 else { return original }
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        pixels.withUnsafeMutableBytes { bytes in
            guard let address = bytes.baseAddress else { return }
            imageContext.render(sample, toBitmap: address, rowBytes: width * 4,
                bounds: bounds, format: .RGBA8, colorSpace: CGColorSpaceCreateDeviceRGB())
        }
        var luminances = [Int]()
        luminances.reserveCapacity(width * height)
        for i in stride(from: 0, to: pixels.count, by: 4) {
            luminances.append(Int(0.2126 * Double(pixels[i]) +
                0.7152 * Double(pixels[i + 1]) + 0.0722 * Double(pixels[i + 2])))
        }
        luminances.sort()
        let median = luminances[luminances.count / 2]
        guard median < 75 else { return original }
        let gamma = min(0.85, max(0.28,
            log(92.0 / 255.0) / log(Double(max(median, 2)) / 255.0)))
        guard let filter = CIFilter(name: "CIGammaAdjust", parameters: [
            kCIInputImageKey: input, "inputPower": gamma
        ]), let output = filter.outputImage,
              let rendered = imageContext.createCGImage(output, from: input.extent) else { return original }
        return UIImage(cgImage: rendered, scale: original.scale, orientation: .up)
    }

    func rename(sessionID: String, to name: String) {
        guard let i = sessions.firstIndex(where: { $0.id == sessionID }) else { return }
        sessions[i].name = name
        save()
    }

    func delete(sessionID: String) {
        guard !syncing, UUID(uuidString: sessionID) != nil,
              let removed = sessions.first(where: { $0.id == sessionID }) else { return }
        let remaining = sessions.filter { $0.id != sessionID }
        let deleted = deletedSessionIDs.union([sessionID])
        let pending = pendingDeletionIDs.union([sessionID])
        do {
            // Persist the deletion and its retry queue in the same atomic write.
            let manifest = MemoryManifest(schemaVersion: 1, sessions: remaining,
                deletedSessionIDs: deleted, pendingDeletionIDs: pending)
            try JSONEncoder().encode(manifest).write(to: index, options: .atomic)
            sessions = remaining
            deletedSessionIDs = deleted
            pendingDeletionIDs = pending
            let retained = Set(remaining.flatMap(\.steps).map(\.photo))
            for step in removed.steps where !retained.contains(step.photo) {
                guard step.photo.range(of: #"^photos/[0-9a-f-]{36}/[0-9a-f-]{36}\.jpg$"#,
                                       options: .regularExpression) != nil else { continue }
                try? FileManager.default.removeItem(at: root.appendingPathComponent(step.photo))
            }
            imageCache.removeAllObjects()
            message = "Deleted from iPhone. Sync to delete it from your glasses."
        } catch {
            message = "Could not save deletion: \(error.localizedDescription)"
        }
    }

    func update(stepID: String, sessionID: String, note: String? = nil, completed: Bool? = nil) {
        guard let i = sessions.firstIndex(where: { $0.id == sessionID }),
              let j = sessions[i].steps.firstIndex(where: { $0.id == stepID }) else { return }
        if let note { sessions[i].steps[j].note = note }
        if let completed { sessions[i].steps[j].completed = completed }
        save()
    }

    func moveSteps(sessionID: String, from source: IndexSet, to destination: Int) {
        guard let i = sessions.firstIndex(where: { $0.id == sessionID }) else { return }
        sessions[i].steps.move(fromOffsets: source, toOffset: destination)
        for j in sessions[i].steps.indices { sessions[i].steps[j].number = j + 1 }
        save()
    }

    func sync(ip: String, code: String) async {
        guard !syncing else { return }
        syncing = true
        defer { syncing = false }
        let host = ip.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !host.isEmpty, !code.isEmpty, !host.contains("/"), !host.contains(":") else {
            message = "Enter the glasses IP address and six digit code."
            return
        }
        guard let base = URL(string: "http://\(host):8765") else {
            message = "Invalid IP address."
            return
        }
        do {
            for id in pendingDeletionIDs.sorted() {
                try await deleteOnGlasses(base.appendingPathComponent("session/\(id)"), code: code)
                pendingDeletionIDs.remove(id)
                save()
            }
            let manifestData = try await fetch(base.appendingPathComponent("manifest"), code: code)
            let incoming = try JSONDecoder().decode(MemoryManifest.self, from: manifestData)
            guard incoming.schemaVersion == 1 else { throw SyncError.unsupportedVersion }
            var added = 0
            let remoteIDs = Set(incoming.sessions.map(\.id))
            let remoteStepIDs = Set(incoming.sessions.flatMap(\.steps).map(\.id))
            for remote in incoming.sessions {
                guard !deletedSessionIDs.contains(remote.id) else { continue }
                let currentIndex = sessions.firstIndex(where: { $0.id == remote.id })
                if currentIndex == nil {
                    sessions.append(MemorySession(id: remote.id, name: remote.name,
                        createdAt: remote.createdAt, steps: [], endedAt: remote.endedAt))
                    save()
                }
                guard let index = sessions.firstIndex(where: { $0.id == remote.id }) else { continue }
                sessions[index].endedAt = remote.endedAt
                for step in remote.steps {
                    guard step.photo.range(of: #"^photos/[0-9a-f-]{36}/[0-9a-f-]{36}\.jpg$"#,
                                                options: .regularExpression) != nil else { continue }
                    let imageURL = root.appendingPathComponent(step.photo)
                    if !FileManager.default.fileExists(atPath: imageURL.path) {
                        let imageData = try await fetch(base.appendingPathComponent("photo/\(step.photo)"), code: code)
                        try FileManager.default.createDirectory(at: imageURL.deletingLastPathComponent(),
                                                                withIntermediateDirectories: true)
                        try imageData.write(to: imageURL, options: .atomic)
                    }
                    if !sessions[index].steps.contains(where: { $0.id == step.id }) {
                        var imported = step
                        let previous = sessions.flatMap(\.steps).first(where: { $0.id == step.id })
                        if let previous {
                            imported.completed = previous.completed
                            if !previous.note.isEmpty { imported.note = previous.note }
                        }
                        sessions[index].steps.append(imported)
                        save()
                        if previous == nil { added += 1 }
                    } else if let localIndex = sessions[index].steps.firstIndex(where: { $0.id == step.id }) {
                        sessions[index].steps[localIndex].number = step.number
                        if sessions[index].steps[localIndex].note.isEmpty && !step.note.isEmpty {
                            sessions[index].steps[localIndex].note = step.note
                        }
                        save()
                    }
                }
                let known = Dictionary(uniqueKeysWithValues: sessions[index].steps.map { ($0.id, $0) })
                let ordered = remote.steps.compactMap { known[$0.id] }
                let extra = sessions[index].steps.filter { !remote.steps.map(\.id).contains($0.id) }
                sessions[index].steps = ordered + extra
                save()
            }
            // A previous glasses version could split one recording across sessions.
            // Remove local copies only when every step now exists in the remote library.
            sessions.removeAll { session in
                !remoteIDs.contains(session.id) &&
                (session.steps.isEmpty || session.steps.allSatisfy { remoteStepIDs.contains($0.id) })
            }
            save()
            message = "Sync complete. New steps: \(added)."
        } catch let error as URLError {
            message = "Cannot reach glasses (code \(error.code.rawValue)). Reconnect to the glasses’ Wi-Fi, allow Local Network access, and keep ReStep open on the glasses."
        } catch {
            message = "Sync: \(error.localizedDescription)"
        }
    }

    private func fetch(_ url: URL, code: String) async throws -> Data {
        var request = URLRequest(url: url)
        request.timeoutInterval = 20
        request.setValue(code, forHTTPHeaderField: "X-Pair-Code")
        let (data, response) = try await transport.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw SyncError.badResponse }
        if http.statusCode == 403 { throw SyncError.badCode }
        guard http.statusCode == 200 else { throw SyncError.badResponse }
        return data
    }

    private func deleteOnGlasses(_ url: URL, code: String) async throws {
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.timeoutInterval = 20
        request.setValue(code, forHTTPHeaderField: "X-Pair-Code")
        let (_, response) = try await transport.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw SyncError.badResponse }
        switch http.statusCode {
        case 204: return
        case 403: throw SyncError.badCode
        case 409: throw SyncError.glassesBusy
        case 404, 405: throw SyncError.updateGlasses
        default: throw SyncError.badResponse
        }
    }

    private func save() {
        let value = MemoryManifest(schemaVersion: 1, sessions: sessions,
            deletedSessionIDs: deletedSessionIDs, pendingDeletionIDs: pendingDeletionIDs)
        if let data = try? JSONEncoder().encode(value) { try? data.write(to: index, options: .atomic) }
    }
}

enum SyncError: LocalizedError {
    case badCode, badResponse, unsupportedVersion, glassesBusy, updateGlasses
    var errorDescription: String? {
        switch self {
        case .badCode: return "incorrect glasses code"
        case .badResponse: return "glasses did not respond"
        case .unsupportedVersion: return "unsupported data version"
        case .glassesBusy: return "Save the current photo on your glasses, then sync again. Deletion is still queued."
        case .updateGlasses: return "Update ReStep on your glasses to version 0.18 or later, then sync again. Deletion is still queued."
        }
    }
}
