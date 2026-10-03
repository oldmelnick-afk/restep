import Foundation

struct MemoryManifest: Codable {
    let schemaVersion: Int
    let sessions: [MemorySession]
    var deletedSessionIDs: Set<String>? = nil
    var pendingDeletionIDs: Set<String>? = nil
}

struct MemorySession: Codable, Identifiable {
    let id: String
    var name: String
    let createdAt: Int64
    var steps: [MemoryStep]
    var endedAt: Int64?

    init(id: String, name: String, createdAt: Int64, steps: [MemoryStep], endedAt: Int64? = nil) {
        self.id = id
        self.name = name
        self.createdAt = createdAt
        self.steps = steps
        self.endedAt = endedAt
    }
}

struct MemoryStep: Codable, Identifiable {
    let id: String
    var number: Int
    let photo: String
    var note: String
    let createdAt: Int64
    var completed: Bool = false

    enum CodingKeys: String, CodingKey {
        case id, number, photo, note, createdAt, completed
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        number = try values.decode(Int.self, forKey: .number)
        photo = try values.decode(String.self, forKey: .photo)
        note = try values.decode(String.self, forKey: .note)
        createdAt = try values.decode(Int64.self, forKey: .createdAt)
        completed = try values.decodeIfPresent(Bool.self, forKey: .completed) ?? false
    }
}
