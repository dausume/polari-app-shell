import SwiftUI
import WebKit

/// WKWebView shell: pinned-CA challenges + the S4 bridge
/// (window.PolariShell over the PolariShellNative message handler).
struct ShellWebView: UIViewRepresentable {
    let instance: InstanceConfig
    let registry: InstanceRegistry

    func makeCoordinator() -> Coordinator {
        Coordinator(instance: instance, registry: registry)
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.userContentController.add(
            context.coordinator, name: "PolariShellNative")
        // The polyfill every platform injects — request() posts the
        // S4 envelope; responses come back via a promise map.
        let polyfill = """
        window.PolariShell = {
          version: 1,
          _pending: {},
          request(msg) {
            return new Promise((resolve) => {
              this._pending[msg.id] = resolve;
              window.webkit.messageHandlers.PolariShellNative
                .postMessage(JSON.stringify(msg));
            });
          },
          _deliver(json) {
            const r = JSON.parse(json);
            const p = this._pending[r.id];
            if (p) { delete this._pending[r.id]; p(r); }
          }
        };
        """
        config.userContentController.addUserScript(WKUserScript(
            source: polyfill, injectionTime: .atDocumentStart,
            forMainFrameOnly: true))
        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = context.coordinator
        if let url = URL(string: instance.webUrl) {
            web.load(URLRequest(url: url))
        }
        return web
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate,
                             WKScriptMessageHandler {
        private let instance: InstanceConfig
        private let registry: InstanceRegistry
        private let trust: PinnedTrust

        init(instance: InstanceConfig,
             registry: InstanceRegistry) {
            self.instance = instance
            self.registry = registry
            self.trust = PinnedTrust(caPems: instance.tls.caPem)
        }

        func webView(_ webView: WKWebView,
                     didReceive challenge: URLAuthenticationChallenge,
                     completionHandler: @escaping
                     (URLSession.AuthChallengeDisposition,
                      URLCredential?) -> Void) {
            trust.urlSession(
                URLSession.shared, didReceive: challenge,
                completionHandler: completionHandler)
        }

        func userContentController(
            _ controller: WKUserContentController,
            didReceive message: WKScriptMessage) {
            guard let json = message.body as? String,
                  let data = json.data(using: .utf8),
                  let msg = try? JSONSerialization
                      .jsonObject(with: data) as? [String: Any],
                  let id = msg["id"] as? String,
                  let type = msg["type"] as? String else { return }
            let payload = respond(type: type, id: id)
            if let out = try? JSONSerialization.data(
                withJSONObject: payload),
               let text = String(data: out, encoding: .utf8) {
                let js = "window.PolariShell._deliver("
                    + jsonStringLiteral(text) + ")"
                message.webView?.evaluateJavaScript(js)
            }
        }

        private func respond(type: String,
                             id: String) -> [String: Any] {
            switch type {
            case "shell.info":
                return ["id": id, "ok": true, "payload": [
                    "shellVersion": "0.1.0",
                    "platform": "ios",
                    "instanceId": instance.id]]
            case "shell.instance.list":
                let list = registry.instances.values.map {
                    ["id": $0.config.id,
                     "displayName": $0.config.displayName,
                     "reachability": [
                        "state": $0.lastProbeState,
                        "evidence": $0.lastProbeEvidence]]
                }
                return ["id": id, "ok": true,
                        "payload": ["instances": list]]
            default:
                return ["id": id, "ok": false, "error": [
                    "code": "unknown-type", "message": type]]
            }
        }

        private func jsonStringLiteral(_ s: String) -> String {
            let data = try? JSONSerialization.data(
                withJSONObject: [s])
            let arr = data.flatMap {
                String(data: $0, encoding: .utf8)
            } ?? "[\"\"]"
            return String(arr.dropFirst().dropLast())
        }
    }
}
