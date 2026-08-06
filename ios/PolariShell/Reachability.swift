import Foundation
import Network

/// Probe-first reachability — SAME truth table and advisory
/// phrasing as :core/net/ReachabilityProbe (keep the tests
/// mirrored or the platforms drift). Probe over a URLSession whose
/// delegate anchors trust to the instance's delivered CA.
enum Reachability {
    static let reachable = "reachable"
    static let wrongNetwork = "wrong-network"
    static let instanceDown = "instance-down"
    static let offline = "offline"
    static let wrongInstance = "wrong-instance"
    static let unknown = "unknown"

    struct Result {
        let state: String
        let evidence: String
    }

    static func probe(_ inst: InstanceConfig) async -> Result {
        let url = inst.reachability.hint.probeUrl.isEmpty
            ? inst.identityUrl : inst.reachability.hint.probeUrl
        guard let probeUrl = URL(string: url), !url.isEmpty else {
            return classify(reachedHttp: false, probeOk: false,
                            rawError: "no probe URL configured",
                            inst: inst,
                            localAddrs: localAddresses())
        }
        let session = URLSession(
            configuration: .ephemeral,
            delegate: PinnedTrust(caPems: inst.tls.caPem),
            delegateQueue: nil)
        var request = URLRequest(url: probeUrl,
                                 timeoutInterval: 3)
        request.httpMethod = "GET"
        do {
            let (data, response) = try await session.data(
                for: request)
            guard let http = response as? HTTPURLResponse,
                  (200...299).contains(http.statusCode) else {
                let code = (response as? HTTPURLResponse)?
                    .statusCode ?? 0
                return classify(reachedHttp: true, probeOk: false,
                                rawError: "identity endpoint "
                                + "answered HTTP \(code)",
                                inst: inst,
                                localAddrs: localAddresses())
            }
            let seen = (try? JSONSerialization.jsonObject(
                with: data) as? [String: Any])?["instanceId"]
                as? String ?? ""
            if !inst.instanceId.isEmpty
                && inst.instanceId != seen {
                return Result(
                    state: wrongInstance,
                    evidence: "identity answered but instanceId "
                    + "is '\(seen)', expected "
                    + "'\(inst.instanceId)'")
            }
            return Result(state: reachable,
                          evidence: "identity endpoint answered "
                          + "with the expected instance")
        } catch {
            return classify(reachedHttp: false, probeOk: false,
                            rawError: String(describing: error),
                            inst: inst,
                            localAddrs: localAddresses())
        }
    }

    /// Pure classification — the shared truth table.
    static func classify(reachedHttp: Bool, probeOk: Bool,
                         rawError: String, inst: InstanceConfig,
                         localAddrs: [String]) -> Result {
        if probeOk {
            return Result(state: reachable,
                          evidence: "probe succeeded")
        }
        if localAddrs.isEmpty {
            return Result(state: offline,
                          evidence: "no network interface is up "
                          + "(\(rawError))")
        }
        let addrs = localAddrs.joined(separator: ", ")
        if reachedHttp {
            return Result(state: instanceDown,
                          evidence: "the server answered but not "
                          + "healthily: \(rawError)")
        }
        let cidrs = inst.reachability.hint.cidrs
        if inst.reachability.scope == "local" && !cidrs.isEmpty {
            let onDeclared = localAddrs.contains {
                inAnyCidr($0, cidrs)
            }
            if !onDeclared {
                return Result(state: wrongNetwork,
                              evidence: "your addresses: \(addrs); "
                              + "expected "
                              + cidrs.joined(separator: ", ")
                              + " (heuristic — subnets can collide "
                              + "across unrelated networks)")
            }
            return Result(state: instanceDown,
                          evidence: "you appear to be on the "
                          + "declared network (\(addrs)) but the "
                          + "instance is not answering: "
                          + rawError)
        }
        if inst.reachability.scope == "local" {
            return Result(state: wrongNetwork,
                          evidence: "instance is declared "
                          + "local-only and did not answer "
                          + "(\(rawError)); your addresses: "
                          + "\(addrs) — no subnet hint declared, "
                          + "so this may also be the instance "
                          + "being down")
        }
        return Result(state: instanceDown,
                      evidence: "web-accessible instance did not "
                      + "answer (\(rawError)) — the instance may "
                      + "be down or this network may block it")
    }

    /// The advisory sentence — identical phrasing everywhere.
    static func advisory(_ inst: InstanceConfig,
                         _ result: Result) -> String {
        let kind = inst.reachability.networkKind.isEmpty
            ? "" : inst.reachability.networkKind + " "
        let name = inst.reachability.networkName.isEmpty
            ? "its network"
            : "the '\(inst.reachability.networkName)' "
              + "\(kind)network"
        let title = inst.displayName.isEmpty
            ? inst.id : inst.displayName
        switch result.state {
        case wrongNetwork:
            return "\(title) lives on \(name) — you don't appear "
                + "to be on it. Connect to that network to use "
                + "this app. (\(result.evidence))"
        case instanceDown:
            return "\(title) is not answering. \(result.evidence)"
        case offline:
            return "No network connectivity. \(result.evidence)"
        case wrongInstance:
            return "This address no longer answers as \(title). "
                + "\(result.evidence)"
        default:
            return "\(title): \(result.evidence)"
        }
    }

    // MARK: - network context (degraded by design on iOS)

    static func localAddresses() -> [String] {
        var out: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0 else { return out }
        defer { freeifaddrs(ifaddr) }
        var ptr = ifaddr
        while let current = ptr {
            let flags = Int32(current.pointee.ifa_flags)
            if let sa = current.pointee.ifa_addr,
               sa.pointee.sa_family == UInt8(AF_INET),
               (flags & IFF_UP) != 0,
               (flags & IFF_LOOPBACK) == 0 {
                var host = [CChar](repeating: 0,
                                   count: Int(NI_MAXHOST))
                if getnameinfo(sa,
                               socklen_t(sa.pointee.sa_len),
                               &host, socklen_t(host.count),
                               nil, 0, NI_NUMERICHOST) == 0 {
                    out.append(String(cString: host))
                }
            }
            ptr = current.pointee.ifa_next
        }
        return out
    }

    static func inAnyCidr(_ address: String,
                          _ cidrs: [String]) -> Bool {
        cidrs.contains { inCidr(address, $0) }
    }

    static func inCidr(_ address: String, _ cidr: String) -> Bool {
        let parts = cidr.split(separator: "/")
        guard parts.count == 2,
              let prefix = Int(parts[1]), prefix >= 0,
              prefix <= 32,
              let net = ipv4(String(parts[0])),
              let addr = ipv4(address) else { return false }
        let mask: UInt32 = prefix == 0
            ? 0 : ~UInt32(0) << (32 - prefix)
        return (net & mask) == (addr & mask)
    }

    private static func ipv4(_ dotted: String) -> UInt32? {
        let octets = dotted.split(separator: ".")
            .compactMap { UInt32($0) }
        guard octets.count == 4,
              octets.allSatisfy({ $0 <= 255 }) else { return nil }
        return octets.reduce(0) { ($0 << 8) | $1 }
    }
}

/// Per-instance trust: anchor SecTrust to exactly the delivered
/// CAs — the URLSession twin of desktop's InstanceTrust. Never a
/// global trust change, never trust-all.
final class PinnedTrust: NSObject, URLSessionDelegate {
    private let anchors: [SecCertificate]

    init(caPems: [String]) {
        anchors = caPems.compactMap { pem in
            let der = pem
                .replacingOccurrences(
                    of: "-----BEGIN CERTIFICATE-----", with: "")
                .replacingOccurrences(
                    of: "-----END CERTIFICATE-----", with: "")
                .replacingOccurrences(of: "\n", with: "")
            guard let data = Data(base64Encoded: der) else {
                return nil
            }
            return SecCertificateCreateWithData(nil, data as CFData)
        }
    }

    func urlSession(_ session: URLSession,
                    didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping
                    (URLSession.AuthChallengeDisposition,
                     URLCredential?) -> Void) {
        guard let trust = challenge.protectionSpace.serverTrust,
              !anchors.isEmpty else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        SecTrustSetAnchorCertificates(trust, anchors as CFArray)
        SecTrustSetAnchorCertificatesOnly(trust, true)
        var error: CFError?
        if SecTrustEvaluateWithError(trust, &error) {
            completionHandler(.useCredential,
                              URLCredential(trust: trust))
        } else {
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}
