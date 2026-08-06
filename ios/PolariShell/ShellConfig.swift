import Foundation

/// Registration document v1 — Codable mirror of
/// config/polari-shell.schema.json. Field names ARE the wire names;
/// change them in lockstep with the backend or not at all.
struct ShellConfig: Codable {
    var kind: String = ""
    var schemaVersion: Int = 0
    var app: AppInfo = AppInfo()
    var instances: [InstanceConfig] = []
    var enrollment: Enrollment?

    struct AppInfo: Codable {
        var name = ""
        var title = ""
        var scope = "instance"
        var appName = ""
        var startRoute = ""
        var brandColor = ""
        var icon = ""
    }

    struct Enrollment: Codable {
        var token = ""
        var redeemUrl = ""
        var expiresAt = ""
    }

    var looksValid: Bool {
        kind == "polari-shell-registration" && schemaVersion == 1
            && !instances.isEmpty
    }
}

struct InstanceConfig: Codable {
    var id = ""
    var displayName = ""
    var webUrl = ""
    var apiUrl = ""
    var identityUrl = ""
    var instanceId = ""
    var auth = Auth()
    var tls = Tls()
    var reachability = Reachability()

    struct Auth: Codable {
        var authority = ""
        var realm = ""
        var clientId = ""
        var pkce = "S256"
        var scope = ""
        var shellRedirectUri = ""
        var loginHint = ""
    }

    struct Tls: Codable {
        var caPem: [String] = []
        var caSha256 = ""
    }

    struct Reachability: Codable {
        var scope = "web"
        var networkKind = ""
        var networkName = ""
        var hint = Hint()

        struct Hint: Codable {
            /// ADVISORY only — the probeUrl is authoritative.
            var cidrs: [String] = []
            var probeUrl = ""
        }
    }
}
