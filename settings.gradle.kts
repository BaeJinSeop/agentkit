plugins {
    // JDK 21 툴체인을 어디서든(로컬·CI·JitPack) 자동으로 내려받게 한다
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "agentkit"
