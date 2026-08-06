// :core — plain Java library, ZERO UI dependencies. Everything the
// platform shells share: config precedence, the instance registry,
// PKCE/OIDC plumbing, CA pinning, reachability probe + classifier,
// polari:// deep links, and the JSON bridge envelope.

plugins {
    `java-library`
}

dependencies {
    api(libs.gson)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
