import Foundation

/// polari://register handling (S2) — short TOFU form + inline
/// payload, same contract as every other shell. The redeem fetch
/// trusts the channel only to CARRY the document; the merge is
/// REFUSED unless the delivered CA hashes to the link's ca= pin.
enum DeepLinkHandler {

    static func handle(_ uri: String,
                       registry: InstanceRegistry) {
        guard uri.hasPrefix("polari://register"),
              let components = URLComponents(string: uri) else {
            return
        }
        var q: [String: String] = [:]
        components.queryItems?.forEach { q[$0.name] = $0.value ?? "" }

        if let payload = q["payload"],
           let data = Data(base64URLEncoded: payload),
           let inst = try? JSONDecoder().decode(
               InstanceConfig.self, from: data) {
            var cfg = ShellConfig()
            cfg.kind = "polari-shell-registration"
            cfg.schemaVersion = 1
            cfg.instances = [inst]
            registry.merge(cfg, source: "deeplink")
            registry.save()
            return
        }
        guard let api = q["api"], let token = q["t"],
              !api.isEmpty, !token.isEmpty else { return }
        Task {
            await redeem(api: api, token: token,
                         caPin: q["ca"] ?? "",
                         registry: registry)
        }
    }

    private static func redeem(api: String, token: String,
                               caPin: String,
                               registry: InstanceRegistry) async {
        guard let url = URL(string: api.trimmingSlash()
            + "/api/appstore/enroll/redeem") else { return }
        var request = URLRequest(url: url, timeoutInterval: 10)
        request.httpMethod = "POST"
        request.setValue("application/json",
                         forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(
            withJSONObject: ["token": token, "platform": "ios",
                             "deviceLabel": "iOS"])
        // TOFU channel: certificate errors accepted for THIS fetch
        // only; the ca= pin below is the real trust root.
        let session = URLSession(
            configuration: .ephemeral,
            delegate: TofuChannel(), delegateQueue: nil)
        guard let (data, response) = try? await session.data(
                for: request),
              (response as? HTTPURLResponse)?.statusCode ?? 0
                  / 100 == 2,
              let doc = try? JSONDecoder().decode(
                  ShellConfig.self, from: data),
              doc.looksValid else { return }
        let pinHolds = caPin.isEmpty || doc.instances.allSatisfy {
            inst in inst.tls.caPem.isEmpty
                || inst.tls.caPem.contains {
                    sha256Hex($0) == caPin.lowercased()
                }
        }
        guard pinHolds else { return }
        await MainActor.run {
            registry.merge(doc, source: "deeplink")
            registry.save()
        }
    }

    private static func sha256Hex(_ s: String) -> String {
        import_CryptoKit_sha256(s)
    }
}

// Kept tiny + separate so the TOFU exception stays visibly scoped.
final class TofuChannel: NSObject, URLSessionDelegate {
    func urlSession(_ session: URLSession,
                    didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping
                    (URLSession.AuthChallengeDisposition,
                     URLCredential?) -> Void) {
        if let trust = challenge.protectionSpace.serverTrust {
            completionHandler(.useCredential,
                              URLCredential(trust: trust))
        } else {
            completionHandler(.performDefaultHandling, nil)
        }
    }
}

import CryptoKit

func import_CryptoKit_sha256(_ s: String) -> String {
    SHA256.hash(data: Data(s.utf8))
        .map { String(format: "%02x", $0) }.joined()
}

extension Data {
    init?(base64URLEncoded input: String) {
        var b64 = input
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64.append("=") }
        self.init(base64Encoded: b64)
    }
}

extension String {
    func trimmingSlash() -> String {
        hasSuffix("/") ? String(dropLast()) : self
    }
}
