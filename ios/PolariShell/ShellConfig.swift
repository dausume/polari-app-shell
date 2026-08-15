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
        /// sep-2: optional so pre-sep documents still decode.
        var capabilities: [String]?

        /// sep-1 (mirror of core's StartUrl.of): a scope=app block
        /// clamps the SPA via `?shellApp=` on the opened URL; blank
        /// startRoute defaults to the app home. iOS installs are
        /// config-free today so the registry carries no app block
        /// yet — the rule lives here in lockstep for when it does.
        func startUrl(base: String) -> String {
            var b = base.trimmingCharacters(in: .whitespaces)
            while b.hasSuffix("/") { b = String(b.dropLast()) }
            var route = startRoute
                .trimmingCharacters(in: .whitespaces)
            let name = appName
                .trimmingCharacters(in: .whitespaces)
            guard scope == "app", !name.isEmpty else {
                return route.isEmpty ? b : b + slashed(route)
            }
            if route.isEmpty { route = "/app/" + name }
            let sep = route.contains("?") ? "&" : "?"
            let enc = name.addingPercentEncoding(
                withAllowedCharacters: .urlQueryAllowed) ?? name
            return b + slashed(route) + sep + "shellApp=" + enc
        }

        private func slashed(_ route: String) -> String {
            route.hasPrefix("/") ? route : "/" + route
        }
    }

    struct Enrollment: Codable {
        var token = ""
        var redeemUrl = ""
        var expiresAt = ""
    }

    var looksValid: Bool {
        kind == "polari-shell-registration" && schemaVersion == 1
            && !instances.isEmpty
            // sep-1: scope=app without appName = broken document
            && (app.scope != "app" || !app.appName.isEmpty)
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
