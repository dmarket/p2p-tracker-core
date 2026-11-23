import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest

/**
 * The TLSN prover both npm packages ship: one exact npm release of @dmarket/steam-provenance. One pin for the two,
 * so the web extension's package and the headless one never carry different provers.
 *
 * The version and the integrity move together, and only towards a release whose `gitHead` is the commit the
 * deployed notary was built from — the prover and the notary must share a dep/tlsn pin, and a mismatch fails as an
 * opaque MPC error, never as a version error. Bump procedure: core/PROVER.md.
 */
object TlsnProverPin {
    const val PACKAGE = "@dmarket/steam-provenance"

    // EXACT, never a range or a dist-tag: `^1.0.1` would also match every later release.
    const val VERSION = "1.2.0"

    // The registry's `dist.integrity` for that version (`npm view <package>@<version> dist.integrity`): the
    // committed anchor for every byte the prover puts into our tarballs, checked by verifyTlsnProver against the
    // lockfile the install actually used. It lives here rather than in a committed yarn.lock because
    // kotlin-js-store/ is not tracked, and tracking it would freeze every transitive npm dependency of the
    // Kotlin/JS toolchain too — this pins the one npm input that ships, and nothing else.
    const val INTEGRITY =
        "sha512-Cr7VipTSdKpuWR4augobZiuMe2sdHShFPSTdQFFaUITW5gsIfS3raUy38+AwEaKj2CJUZD1EXS+yxS9VfnG+MQ=="

    /** The paths a distribution takes from the installed package: the prover, its transport, and its provenance. */
    val SHIPPED = listOf("pkg/**", "transport/**", "VERSION")
}

/**
 * The checks listed above verifyTlsnProver's registration in core/build.gradle.kts. A task type rather than a
 * doFirst so its state is plain properties: nothing at execution time reaches back into the build script or the
 * Project.
 *
 * Up-to-date only when the pin, the lockfile and every byte under the installed package are what they were
 * at the last pass, which is the same thing as passing again. Not cacheable: a cache hit would restore a stamp
 * for a package this machine never looked at.
 */
abstract class VerifyTlsnProver : DefaultTask() {
    @get:Input abstract val packageName: Property<String>

    @get:Input abstract val packageVersion: Property<String>

    @get:Input abstract val packageIntegrity: Property<String>

    /** VERSION_NAME of the library being built: decides whether a snapshot prover is allowed. */
    @get:Input abstract val releaseVersion: Property<String>

    // @InputFiles, not @InputDirectory / @InputFile: those reject a missing path with Gradle's own validation
    // error before the action runs. A missing package is the failure that matters most — a Copy from an absent
    // directory succeeds and copies nothing, which would publish a tarball whose notary path 404s on the first
    // proof — so it has to reach verify() and fail with a message that says where to look.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packageDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val installLock: RegularFileProperty

    @get:OutputFile abstract val stamp: RegularFileProperty

    @TaskAction
    fun verify() {
        val pkg = packageName.get()
        val version = packageVersion.get()
        val integrity = packageIntegrity.get()
        val dir = packageDir.get().asFile
        val lock = installLock.get().asFile

        fun fail(why: String): Nothing = throw GradleException("TLSN prover $pkg@$version: $why. See core/PROVER.md.")

        fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
            "%02x".format(it)
        }

        // 5. Channel, checked first because it needs nothing installed: a stable release (VERSION_NAME without
        //    -SNAPSHOT) ships only a stable prover. A snapshot prover is a development build of steam-provenance,
        //    and nothing else stops it from reaching npm `latest` after a development -> main merge.
        val release = releaseVersion.get()
        if (!release.endsWith("-SNAPSHOT") && version.contains("SNAPSHOT", ignoreCase = true)) {
            fail("a stable release ($release) cannot ship a snapshot prover; pin a stable release in TlsnProverPin")
        }

        if (!dir.resolve("pkg/client_wasm_bg.wasm").isFile) fail("not installed at $dir")

        // 1. Integrity of the npm tarball, as recorded by the install that produced `dir`. yarn verifies the
        //    download against the registry's integrity and writes that value into the lockfile, so comparing
        //    the lockfile entry with the pin is comparing the installed tarball with the pin.
        if (!lock.isFile) fail("no lockfile at $lock to read the installed integrity from")
        val entries = lock.readText().split(Regex("\n\\s*\n")).filter {
            it.trimStart().removePrefix("\"").startsWith("$pkg@")
        }
        if (entries.size != 1) fail("expected exactly one $pkg entry in $lock, found ${entries.size}")
        val fields = entries.single().lines().map { it.trim() }
        val lockedVersion = fields.firstOrNull { it.startsWith("version ") }?.removePrefix("version ")?.trim('"')
        val lockedIntegrity = fields.firstOrNull { it.startsWith("integrity ") }?.removePrefix("integrity ")
        if (lockedVersion != version) fail("$lock resolved version $lockedVersion")
        if (lockedIntegrity?.split(' ')?.contains(integrity) != true) {
            fail("installed integrity $lockedIntegrity is not the pinned $integrity")
        }

        // 2. The installed manifest, and the commit it names.
        val manifest = groovy.json.JsonSlurper().parse(dir.resolve("package.json")) as Map<*, *>
        if (manifest["version"] != version) fail("installed package.json is version ${manifest["version"]}")
        val provenance = dir.resolve("VERSION").readLines()
            .mapNotNull { Regex("""^([A-Z0-9_]+)="(.*)"$""").find(it.trim())?.destructured }
            .associate { (key, value) -> key to value }
        val gitSha = provenance["GIT_SHA"] ?: fail("VERSION has no GIT_SHA")
        if (manifest["gitHead"] != gitSha) fail("package.json gitHead ${manifest["gitHead"]} != VERSION GIT_SHA $gitSha")

        // 3. SHA256SUMS over everything we are about to ship, in both directions. A shipped file the manifest
        //    does not list would reach every consumer without having been checked by anything. A listed file
        //    that is missing would ship a prover that loads and then dies — the spawn.js snippet under pkg/ is
        //    only fetched once initialize() starts its workers — with nothing failing at build time.
        val sums = dir.resolve("SHA256SUMS").readLines().filter { it.isNotBlank() }.associate { line ->
            val match = Regex("""^([0-9a-f]{64}) [ *](.+)$""").find(line) ?: fail("malformed SHA256SUMS line: $line")
            match.groupValues[2] to match.groupValues[1]
        }

        // The same set the distributions copy (TlsnProverPin.SHIPPED: pkg/**, transport/**, VERSION).
        fun shipped(path: String) = path.startsWith("pkg/") || path.startsWith("transport/") || path == "VERSION"
        val installed = dir.walkTopDown().filter(File::isFile)
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .filter(::shipped)
            .toSortedSet()
        val listed = sums.keys.filter(::shipped).toSortedSet()
        (installed - listed).firstOrNull()?.let { fail("$it is not covered by SHA256SUMS") }
        (listed - installed).firstOrNull()?.let { fail("$it is listed in SHA256SUMS but not installed") }
        installed.forEach { path ->
            if (sha256(dir.resolve(path)) != sums.getValue(path)) fail("$path does not match SHA256SUMS")
        }

        // 4. The provenance manifest describes the binary that is actually there (verified against SHA256SUMS
        //    just above, so its listed digest is the one to compare).
        val wasmSha = sums.getValue("pkg/client_wasm_bg.wasm")
        if (provenance["WASM_SHA256"] != wasmSha) {
            fail("VERSION WASM_SHA256 ${provenance["WASM_SHA256"]} != actual $wasmSha")
        }

        val verdict = "TLSN prover $pkg@$version verified (${installed.size} files): " +
            "GIT_SHA=$gitSha WASM_SHA256=$wasmSha TRANSPORT_VERSION=${provenance["TRANSPORT_VERSION"]}"
        stamp.get().asFile.writeText(verdict + "\n")
        logger.lifecycle(verdict)
    }
}

/**
 * The Android C ABI the same pinned package ships under `native/android/`, checked and staged for the
 * :prover-android AAR. It runs after verifyTlsnProver, which has already tied the whole npm tarball to
 * TlsnProverPin.INTEGRITY and the package's gitHead to VERSION's GIT_SHA; the package's SHA256SUMS does not
 * cover native/, so this task carries the chain on through native/android/BUILD.json:
 *   1. BUILD.json is for `android` and names the installed package.json's gitHead, which is VERSION's GIT_SHA;
 *   2. every file taken (the three `.so`, the header, the notices) has the SHA-256 BUILD.json lists for it, and
 *      BUILD.json lists `.so` files for exactly [abis];
 *   3. the notices committed under [committedNotices] are the package's, byte for byte, so a bump that changes
 *      them fails until the copies in licenses/ are refreshed.
 * Any miss fails the build. The bytes are copied unchanged: `<abi>/` under [jniLibsDir], the header under [includeDir].
 */
abstract class VerifyProverNativeLibs : DefaultTask() {
    @get:Input abstract val packageVersion: Property<String>

    @get:Input abstract val abis: org.gradle.api.provider.ListProperty<String>

    // @InputFiles for the same reason as VerifyTlsnProver.packageDir: a missing install must reach verify().
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packageDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val committedNotices: DirectoryProperty

    @get:org.gradle.api.tasks.OutputDirectory abstract val jniLibsDir: DirectoryProperty

    @get:org.gradle.api.tasks.OutputDirectory abstract val includeDir: DirectoryProperty

    @TaskAction
    fun verify() {
        val pkg = packageDir.get().asFile
        val native = pkg.resolve("native/android")
        val version = packageVersion.get()

        fun fail(why: String): Nothing =
            throw GradleException("TLSN prover ${TlsnProverPin.PACKAGE}@$version native/android: $why. See core/PROVER.md.")

        fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
            "%02x".format(it)
        }

        val buildJson = native.resolve("BUILD.json")
        if (!buildJson.isFile) fail("no BUILD.json at $buildJson")
        val build = groovy.json.JsonSlurper().parse(buildJson) as Map<*, *>
        val manifest = groovy.json.JsonSlurper().parse(pkg.resolve("package.json")) as Map<*, *>
        if (manifest["version"] != version) fail("installed package.json is version ${manifest["version"]}")
        val gitSha = pkg.resolve("VERSION").readLines()
            .firstNotNullOfOrNull { Regex("""^GIT_SHA="(.*)"$""").find(it.trim())?.groupValues?.get(1) }
            ?: fail("VERSION has no GIT_SHA")

        // 1. One commit across BUILD.json, package.json and VERSION.
        if (build["platform"] != "android") fail("BUILD.json platform is ${build["platform"]}")
        if (build["gitHead"] != manifest["gitHead"] || build["gitHead"] != gitSha) {
            fail("BUILD.json gitHead ${build["gitHead"]} != package.json gitHead ${manifest["gitHead"]} / VERSION GIT_SHA $gitSha")
        }

        // 2. Every file taken matches BUILD.json, and the ABI set is the one we package.
        val files = (build["files"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v.toString() }
            ?: fail("BUILD.json has no files")
        val libName = "libprovenance_client_cffi.so"
        val listedAbis = files.keys.filter { it.endsWith("/$libName") }.map { it.substringBefore('/') }.toSortedSet()
        if (listedAbis != abis.get().toSortedSet()) fail("BUILD.json lists $libName for $listedAbis, expected ${abis.get()}")
        val notices = listOf("THIRD_PARTY_NOTICES.md", "RUST_STDLIB_LICENSE.html")
        val taken = abis.get().map { "$it/$libName" } + "include/provenance_client.h" + notices
        taken.forEach { path ->
            val file = native.resolve(path)
            if (!file.isFile) fail("$path is listed in BUILD.json but not installed")
            if (sha256(file) != files[path]) fail("$path does not match its SHA-256 in BUILD.json")
        }

        // 3. The committed notices are the package's.
        notices.forEach { name ->
            val committed = committedNotices.get().asFile.resolve(name)
            if (!committed.isFile || sha256(committed) != files[name]) {
                fail("$committed is not the package's $name; copy native/android/$name from the pinned package")
            }
        }

        val libs = jniLibsDir.get().asFile.apply { deleteRecursively() }
        abis.get().forEach { abi -> native.resolve("$abi/$libName").copyTo(libs.resolve("$abi/$libName")) }
        val include = includeDir.get().asFile.apply { deleteRecursively() }
        native.resolve("include/provenance_client.h").copyTo(include.resolve("provenance_client.h"))
        logger.lifecycle(
            "TLSN prover native/android verified: gitHead=$gitSha " +
                abis.get().joinToString(" ") { "$it=${files["$it/$libName"]}" },
        )
    }
}
