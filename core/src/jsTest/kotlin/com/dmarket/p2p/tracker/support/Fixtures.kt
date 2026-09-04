package com.dmarket.p2p.tracker.support

// KGP places commonTest/resources alongside the compiled test bundle. `__dirname`, its parent,
// and `process.cwd()` cover variation across KGP versions so the path resolves on any Kotlin 2.x.
actual fun fixture(name: String): String {
    val fs: dynamic = js("require('fs')")
    val p: dynamic = js("require('path')")
    val dir = js("__dirname").unsafeCast<String>()
    val cwd = js("process.cwd()").unsafeCast<String>()

    for (base in listOf(dir, p.join(dir, "..").unsafeCast<String>(), cwd)) {
        val full = p.join(base, "fixtures", name).unsafeCast<String>()
        try {
            return fs.readFileSync(full, "utf-8").unsafeCast<String>()
        } catch (_: Throwable) {}
    }
    error("Fixture not found: fixtures/$name")
}
