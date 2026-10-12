// JVM stand-in for what Mihon/Komikku provide to an extension at runtime:
// the real (non-stub) extensions-lib 1.6 API, a NetworkHelper, a few android.* shims, and the CLI.
// Versions mirror keiyoushi's gradle/libs.versions.toml (bundles.common).
plugins {
    kotlin("jvm") version "2.4.10"
}

// Keep build output out of the skill dir.
System.getenv("EXT_RUN_BUILD_DIR")?.let { layout.buildDirectory.set(file(it)) }

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xcontext-parameters")
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.4.10")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-okio:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("com.squareup.okhttp3:okhttp-brotli:5.4.0")
    implementation("com.squareup.okhttp3:okhttp-zstd:5.4.0")
    implementation("com.squareup.zstd:zstd-kmp-okio:0.4.0")
    // The app provides jsoup at runtime: Mihon v0.20.4 ships 1.23.1 (extensions compile against 1.22.2).
    implementation("org.jsoup:jsoup:1.23.1")
    implementation("org.jspecify:jspecify:1.0.1")
    implementation("com.github.null2264.injekt:injekt-core:4135455a2a")
    implementation("io.reactivex:rxjava:1.3.8")
    // Android's org.json is a framework class; this is Android's own implementation, published
    // for JVM use. Its quirks (getString coercion, optX defaults) match the device, unlike JSON-java's.
    implementation("com.vaadin.external.google:android-json:0.0.20131108.vaadin1")
    // QuickJS (app.cash.quickjs) is an Android-native lib the app provides; GraalJS stands in for it.
    implementation("org.graalvm.polyglot:polyglot:24.2.1")
    implementation("org.graalvm.polyglot:js-community:24.2.1")
    // WebP decoding for the android.graphics shim (BitmapFactory). Read-only: there's no WebP
    // ImageIO *writer* here, so Bitmap.compress(WEBP, ...) falls back to PNG bytes.
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
    // Android's security providers (Conscrypt, BC) accept "AES/CBC/PKCS7Padding" etc.; the JDK's SunJCE
    // doesn't. Registered last (Main.kt), as a fallback for what the JDK lacks.
    implementation("org.bouncycastle:bcprov-jdk18on:1.82")
}

// Runtime jar + every dependency jar in one dir; ext-run.sh puts <dir>/* on the classpath.
val dist by tasks.registering(Sync::class) {
    from(tasks.named("jar"))
    from(configurations.named("runtimeClasspath"))
    into(layout.buildDirectory.dir("dist"))
}
