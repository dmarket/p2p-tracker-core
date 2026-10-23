package com.dmarket.p2p.headless

import com.dmarket.p2p.headless.node.nodeFs
import com.dmarket.p2p.headless.node.nodePath
import com.dmarket.p2p.headless.node.nodeProcess
import com.dmarket.p2p.headless.prover.resolveInstalled

/**
 * The files a prover process needs: [script], the child entry this package ships, and [assetsDir], which holds the
 * prover's `pkg/` and `transport/`.
 */
internal class ProverFiles(val script: String, val assetsDir: String) {
    companion object {
        /** This package's name: the child script and, by default, the prover's files are found through it. */
        const val PACKAGE = "@dmarket/p2p-tracker-headless"

        /** The child entry in the package's root. */
        const val CHILD_SCRIPT = "prover-child.mjs"

        /**
         * The child script in [packageRoot] (this package's directory when `null`, found the way the host's own
         * entry would import it) and the prover's files in [assetsDir] (the package root when `null`). Throws
         * "prover files not found", naming the first file that is not there: a broken install shows when the
         * tracker is built, not on the first deal that needs a proof.
         */
        fun locate(packageRoot: String?, assetsDir: String?): ProverFiles {
            val root = packageRoot ?: installedRoot() ?: error("prover files not found: $PACKAGE/package.json cannot be resolved")
            val files = ProverFiles(script = join(root, CHILD_SCRIPT), assetsDir = assetsDir ?: root)
            val required = listOf(
                files.script,
                join(files.assetsDir, "pkg", "client_wasm_bg.wasm"),
                join(files.assetsDir, "transport", "dist", "node.js"),
            )
            required.firstOrNull { !(nodeFs.existsSync(it) as Boolean) }?.let { error("prover files not found: $it is missing") }
            return files
        }

        /** The package's directory, from its `package.json` as seen from the process's entry script. */
        private fun installedRoot(): String? =
            resolveInstalled("$PACKAGE/package.json", nodeProcess.argv[1] as? String)?.let { nodePath.dirname(it) as String }

        private fun join(vararg parts: String): String = parts.reduce { dir, part -> nodePath.join(dir, part) as String }
    }
}
