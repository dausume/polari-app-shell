import Foundation

/// Registered instance + shell-maintained state (mirrors
/// :core/registry). Merge is ADD-ONLY: an arriving same-id
/// different-URL registration is recorded as a conflict, never an
/// overwrite.
struct RegisteredInstance: Codable {
    var config = InstanceConfig()
    var source = ""
    var enrolled = false
    var userClassified = false
    var lastProbeState = ""
    var lastProbeEvidence = ""
}

final class InstanceRegistry: ObservableObject {
    @Published private(set) var instances: [String: RegisteredInstance] = [:]
    @Published private(set) var conflicts: [String] = []
    var lastUsedId = ""

    private let file: URL

    init(file: URL? = nil) {
        self.file = file ?? FileManager.default
            .urls(for: .applicationSupportDirectory,
                  in: .userDomainMask)[0]
            .appendingPathComponent("polari-shell/instances.json")
        load()
    }

    func load() {
        guard let data = try? Data(contentsOf: file),
              let stored = try? JSONDecoder()
                  .decode(Persisted.self, from: data) else { return }
        instances = stored.instances
        lastUsedId = stored.lastUsedId
    }

    func save() {
        try? FileManager.default.createDirectory(
            at: file.deletingLastPathComponent(),
            withIntermediateDirectories: true)
        let stored = Persisted(instances: instances,
                               lastUsedId: lastUsedId)
        if let data = try? JSONEncoder().encode(stored) {
            try? data.write(to: file,
                            options: [.completeFileProtection])
        }
    }

    @discardableResult
    func merge(_ cfg: ShellConfig, source: String) -> [String] {
        var added: [String] = []
        for inst in cfg.instances where !inst.id.isEmpty {
            if let existing = instances[inst.id] {
                if existing.config.webUrl != inst.webUrl {
                    conflicts.append(
                        "\(inst.id): registered "
                        + "\(existing.config.webUrl) vs arriving "
                        + "\(inst.webUrl) (\(source))")
                }
            } else {
                var reg = RegisteredInstance()
                reg.config = inst
                reg.source = source
                instances[inst.id] = reg
                added.append(inst.id)
            }
        }
        return added
    }

    private struct Persisted: Codable {
        var instances: [String: RegisteredInstance]
        var lastUsedId: String
    }
}
