import XCTest
@testable import PolariShell

/// MIRROR of :core's ReachabilityTest truth table — if one side
/// changes, change both or the platforms drift.
final class ReachabilityTests: XCTestCase {

    private func local(_ cidrs: [String]) -> InstanceConfig {
        var i = InstanceConfig()
        i.id = "prf-a"
        i.displayName = "Climate Lab"
        i.reachability.scope = "local"
        i.reachability.networkKind = "home"
        i.reachability.networkName = "Etts Home Network"
        i.reachability.hint.cidrs = cidrs
        return i
    }

    func testCidrMembership() {
        XCTAssertTrue(Reachability.inCidr("192.168.0.42",
                                          "192.168.0.0/24"))
        XCTAssertFalse(Reachability.inCidr("192.168.1.42",
                                           "192.168.0.0/24"))
        XCTAssertTrue(Reachability.inCidr("10.8.0.5", "10.0.0.0/8"))
        XCTAssertFalse(Reachability.inCidr("not-an-ip",
                                           "192.168.0.0/24"))
    }

    func testClassificationTable() {
        let inst = local(["192.168.0.0/24"])
        XCTAssertEqual(Reachability.classify(
            reachedHttp: false, probeOk: false,
            rawError: "x", inst: inst, localAddrs: []).state,
            Reachability.offline)
        let wrong = Reachability.classify(
            reachedHttp: false, probeOk: false, rawError: "x",
            inst: inst, localAddrs: ["10.0.0.7"])
        XCTAssertEqual(wrong.state, Reachability.wrongNetwork)
        XCTAssertTrue(wrong.evidence.contains("10.0.0.7"))
        XCTAssertEqual(Reachability.classify(
            reachedHttp: false, probeOk: false, rawError: "x",
            inst: inst, localAddrs: ["192.168.0.42"]).state,
            Reachability.instanceDown)
        XCTAssertEqual(Reachability.classify(
            reachedHttp: true, probeOk: false, rawError: "503",
            inst: inst, localAddrs: ["10.0.0.7"]).state,
            Reachability.instanceDown)
    }

    func testAdvisoryPhrasing() {
        let inst = local(["192.168.0.0/24"])
        let wrong = Reachability.classify(
            reachedHttp: false, probeOk: false, rawError: "x",
            inst: inst, localAddrs: ["10.0.0.7"])
        let text = Reachability.advisory(inst, wrong)
        XCTAssertTrue(text.contains("Climate Lab"))
        XCTAssertTrue(text.contains(
            "'Etts Home Network' home network"))
        XCTAssertTrue(text.contains("Connect to that network"))
    }
}
