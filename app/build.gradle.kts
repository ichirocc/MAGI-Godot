// [Gradle 9移行] AGP 9.0+ の内蔵Kotlinサポートを使用（org.jetbrains.kotlin.android は不要。
// Kotlin 版数は root build.gradle.kts の buildscript classpath で 2.3.21 に固定）。
// UI は Godot 4.5.1（godot/）＋ Kotlin ブリッジ（godot/ パッケージ）。Compose UI は 3.549.0 で削除。
plugins {
    id("com.android.application")
}

android {
    namespace = "com.magi.app"
    // compileSdk は 36 のまま。当初 37 へ上げたが CI（Release Build）で
    //   sdkmanager が "Failed to find package 'platforms;android-37'" となり当時は未提供だった（run 29385635188）。
    //   [3.426.0 更新] 3.409.12 で SDK リポジトリを直接確認: 素の 'platforms;android-37' は存在せず
    //   'platforms;android-37.0'（stable・revision 2）と 'build-tools;37.0.0' が公開済み＝「未公開」は解消。
    //   残る移行ゲートは (a) compileSdk=37 が AGP 9.1.1 でマイナー付き 37.0 へ解決するかの CI 確認
    //   (b) targetSdk まで上げるかは Android 17 の挙動変更を受け入れる製品判断。
    compileSdk = 36
    // [ネイティブ加速] NDK を明示固定（CI と開発環境で同一ビルドを保証。AGP の既定NDKに追従させない）。
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.magi.app"
        minSdk = 36
        targetSdk = 36
        versionCode = 770
        versionName = "3.550.0-godot-ui"
        // [ネイティブ加速] minSdk 36（Android 16+）の実機は arm64 のみ対象で十分。
        //   .so が無い環境でも NativeBridge が false を返し Kotlin パスで全機能が動く。
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            // Personal-test release APK: signed with the debug key so it is installable from Actions.
            // Replace with a private release signingConfig before store distribution.
            signingConfig = signingConfigs.getByName("debug")
            // No shrinking for this personal-test build. proguardFiles() is intentionally omitted:
            // it is ignored while isMinifyEnabled = false and only invites the false impression that
            // shrink rules are active. Re-add it together with isMinifyEnabled = true for a store build.
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // [Gradle 9移行] kotlinOptions{jvmTarget}は撤去。内蔵Kotlinは既定で
    // android.compileOptions.targetCompatibility(=17, 上記)からjvmTargetを継承するため明示不要
    // （公式ドキュメント確認: "You don't need to explicitly set jvmTarget... it defaults to
    // android.compileOptions.targetCompatibility"）。
    packaging { resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}") } }
    // magi.pck は非圧縮で同梱する。MagiStartupGuard が filesDir へ複製できず res:// で直読みする退避経路では
    // Godot が AAsset で seek するため、圧縮エントリだと展開し直しが走る。
    androidResources { noCompress += listOf("pck") }

    // This release variant is a personal-test APK signed with the debug key (see buildTypes.release),
    // not a Play-store build. `lintVitalRelease` aborts the APK on any *fatal* lint issue, which only
    // blocks the test build without adding value here. Don't fail the build on lint; still emit the
    // HTML/XML report so issues remain inspectable in app/build/reports/.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
        htmlReport = true
        xmlReport = true
    }
}

// [Godot UI] godot/（.tscn/.gd/project.godot）を PCK に固めて assets へ同梱する。この工程が無いと
// MagiGodotActivity は起動しても読み込む画面を持たない。PCK 化には Godot 本体が要るため
// -PgodotExecutable を必須にし、無ければ黙って空APKを作らず設定段階で落とす。
val godotExecutable = (project.findProperty("godotExecutable") as String?)?.takeIf { it.isNotBlank() }
    ?: throw GradleException(
        "-PgodotExecutable=/absolute/path/to/godot が必要（UI は Godot 版のみ＝godot/ を magi.pck へ export して assets に同梱する。" +
            "CI は .github/workflows/godot-ui-check.yml が Godot 4.5.1 を取得して渡す）"
    )
val godotProjectDir = rootProject.file("godot")
val godotPckDir = layout.buildDirectory.dir("generated/godot-assets")
// export の前に一度 import を走らせ、.godot/ の import キャッシュ（icon.svg 等）を作る。
// 未 import のまま --export-pack すると、インポート済みリソースが無いとして失敗しうる。
val importGodotProject = tasks.register<Exec>("importGodotProject") {
    description = "godot/ のリソースを headless で import する（export の前提）"
    inputs.dir(godotProjectDir)
    outputs.dir(godotProjectDir.resolve(".godot"))
    workingDir = godotProjectDir
    commandLine(godotExecutable, "--headless", "--path", godotProjectDir.absolutePath, "--import")
}
// --export-pack は export templates（約1GB）が無いと拒否されるため、tools/build_pck.gd（PCKPacker）で詰める。
val exportGodotPck = tasks.register<Exec>("exportGodotPck") {
    description = "godot/ を magi.pck に固めて APK の assets へ同梱する"
    dependsOn(importGodotProject)
    inputs.dir(godotProjectDir)
    outputs.dir(godotPckDir)
    doFirst { godotPckDir.get().asFile.mkdirs() }
    workingDir = godotProjectDir
    commandLine(
        godotExecutable, "--headless", "--path", godotProjectDir.absolutePath,
        "--script", "res://tools/build_pck.gd", "--", godotPckDir.get().file("magi.pck").asFile.absolutePath,
    )
}
// AGP は Provider を srcDir に渡すことを禁止する（static/generated の判別不能）。File で渡し、
// タスク依存は上の preBuild.dependsOn で明示する。
android.sourceSets.getByName("main").assets.srcDir(godotPckDir.get().asFile)
tasks.named("preBuild") { dependsOn(exportGodotPck) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // MagiViewModel（AndroidViewModel / viewModelScope）と MagiGodotActivity の ViewModelProvider 用。
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 長時間の最適化計算をバックグラウンドで完遂させる（改善仕様書 §6 / §3.4）
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Godot UI レイヤー本体（CI の Godot UI Check で解決・リンク済み＝docs/godot-ui-migration.md 第5段）。
    implementation("org.godotengine:godot:4.5.1.stable")
    // GodotFragment を載せる FragmentActivity 用。godot AAR の fragment 依存は runtime スコープで
    // 公開されるためコンパイル時に届かない可能性があり、明示する。
    implementation("androidx.fragment:fragment:1.8.5")

    testImplementation("junit:junit:4.13.2")
    // Real org.json on the unit-test classpath so StateParser (org.json) runs in JVM tests
    // (android.jar ships only throwing stubs). Used by the Web-golden parity test.
    testImplementation("org.json:json:20240303")
}

// Surface test stdout (the Web-golden breakdown comparison) in CI console logs.
tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
