#if DELETION_CHECKS
import Foundation
import Darwin

private final class DeletionProtocol: URLProtocol {
    static var deletionStatus = 409
    static var requests: [String] = []
    static var manifest = Data()
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let method = request.httpMethod ?? "GET"
        Self.requests.append("\(method) \(request.url!.path)")
        let status = method == "DELETE" ? Self.deletionStatus : 200
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if method == "GET" { client?.urlProtocol(self, didLoad: Self.manifest) }
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}

enum DeletionChecks {
    @MainActor static func run() async {
        do {
            let id = "11111111-1111-1111-1111-111111111111"
            let other = "22222222-2222-2222-2222-222222222222"
            let photo = "photos/\(id)/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.jpg"
            let shared = "photos/\(id)/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb.jpg"
            let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            let seed: [String: Any] = ["schemaVersion": 1, "sessions": [
                ["id": id, "name": "Delete me", "createdAt": 1, "steps": [
                    ["id": "a", "number": 1, "photo": photo, "note": "remove", "createdAt": 1],
                    ["id": "b", "number": 2, "photo": shared, "note": "shared", "createdAt": 1]]],
                ["id": other, "name": "Keep me", "createdAt": 2, "steps": [
                    ["id": "c", "number": 1, "photo": shared, "note": "keep", "createdAt": 2]]]
            ]]
            let data = try JSONSerialization.data(withJSONObject: seed)
            let storage = root
            try FileManager.default.createDirectory(at: storage.appendingPathComponent(photo).deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: storage.appendingPathComponent("library.json"))
            try Data([1]).write(to: storage.appendingPathComponent(photo))
            try Data([2]).write(to: storage.appendingPathComponent(shared))
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [DeletionProtocol.self]
            let transport = URLSession(configuration: config)
            defer { transport.invalidateAndCancel(); try? FileManager.default.removeItem(at: root) }
            let library = MemoryLibrary(storageRoot: root, transport: transport)
            precondition(library.sessions.count == 2, "Old library must load")
            library.delete(sessionID: id)
            precondition(library.sessions.map(\.id) == [other])
            precondition(library.pendingDeletionIDs == [id])
            precondition(!FileManager.default.fileExists(atPath: storage.appendingPathComponent(photo).path))
            precondition(FileManager.default.fileExists(atPath: storage.appendingPathComponent(shared).path))
            let reopened = MemoryLibrary(storageRoot: root, transport: transport)
            precondition(reopened.pendingDeletionIDs == [id], "Deletion must survive restart")
            DeletionProtocol.manifest = data // Deliberately stale manifest must not resurrect the deleted session.
            await reopened.sync(ip: "127.0.0.1", code: "test")
            precondition(reopened.pendingDeletionIDs == [id], "Conflict must remain queued")
            precondition(DeletionProtocol.requests.count == 1, "Do not import after failed deletion")
            DeletionProtocol.deletionStatus = 204
            await reopened.sync(ip: "127.0.0.1", code: "test")
            precondition(reopened.pendingDeletionIDs.isEmpty)
            precondition(reopened.sessions.map(\.id) == [other], "Do not resurrect deleted data")
            precondition(Array(DeletionProtocol.requests.suffix(2)) == ["DELETE /session/\(id)", "GET /manifest"])
            let final = MemoryLibrary(storageRoot: root, transport: transport)
            precondition(final.pendingDeletionIDs.isEmpty && final.sessions.map(\.id) == [other])
            print("DELETION_CHECKS_PASSED")
            exit(0)
        } catch { print("DELETION_CHECKS_FAILED: \(error)"); exit(1) }
    }
}
#endif
