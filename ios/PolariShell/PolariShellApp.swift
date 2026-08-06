import SwiftUI

/// The iOS shell (plan phase 4): config-free install (App Store
/// review forbids per-user binaries), registration arrives by
/// polari:// deep link / QR, then the same sequence as every other
/// platform: registry -> probe -> advisory or open.
@main
struct PolariShellApp: App {
    @StateObject private var registry = InstanceRegistry()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(registry)
                .onOpenURL { url in
                    DeepLinkHandler.handle(url.absoluteString,
                                           registry: registry)
                }
        }
    }
}

struct ContentView: View {
    @EnvironmentObject var registry: InstanceRegistry
    @State private var probe: Reachability.Result?
    @State private var current: RegisteredInstance?
    @State private var openAnyway = false

    var body: some View {
        Group {
            if let inst = current {
                if openAnyway
                    || probe?.state == Reachability.reachable {
                    ShellWebView(instance: inst.config,
                                 registry: registry)
                        .ignoresSafeArea(edges: .bottom)
                } else if let probe {
                    advisoryView(inst, probe)
                } else {
                    ProgressView("probing "
                                 + inst.config.webUrl + " …")
                }
            } else {
                emptyState
            }
        }
        .task { await start() }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Text("Polari").font(.title)
            Text("No registered instances. Scan or open a "
                 + "polari:// registration link from an "
                 + "instance's App Store page.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
        }
        .padding()
    }

    private func advisoryView(_ inst: RegisteredInstance,
                              _ result: Reachability.Result)
        -> some View {
        VStack(spacing: 16) {
            Text(result.state)
                .font(.caption).padding(6)
                .background(.orange.opacity(0.2))
                .clipShape(Capsule())
            Text(Reachability.advisory(inst.config, result))
                .multilineTextAlignment(.center)
            HStack {
                Button("Retry") { Task { await start() } }
                Button("Open anyway") { openAnyway = true }
            }
            .buttonStyle(.bordered)
        }
        .padding()
    }

    private func start() async {
        guard let inst = registry.instances[
            registry.lastUsedId]
            ?? registry.instances.values.first else { return }
        current = inst
        probe = await Reachability.probe(inst.config)
        if var updated = registry.instances[inst.config.id],
           let probe {
            updated.lastProbeState = probe.state
            updated.lastProbeEvidence = probe.evidence
        }
        registry.save()
    }
}
