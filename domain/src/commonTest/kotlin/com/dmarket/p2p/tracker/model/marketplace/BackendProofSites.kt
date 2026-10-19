package com.dmarket.p2p.tracker.model.marketplace

/**
 * The backend's proof sites, vendored from the fixture its own tests generate from the table it enforces
 * (`resources/fixtures/proof_sites.json` in the p2p service, as merged on 2026-10-19): each site, by axis, to the raw
 * Steam codes that reach it. Copied as is, without the file's `comment` member.
 *
 * To update: take the new file from the service's `main`, replace the `sites` object below with its `sites`, and run
 * `ProofSiteTest`. A failure there is a site or a code [ProofSite] has to follow.
 */
internal val BACKEND_PROOF_SITES_JSON: String = """
    {
      "sites": {
        "offer_accepted": {
          "offer": [
            3
          ]
        },
        "offer_cancelled": {
          "offer": [
            6,
            8,
            10
          ]
        },
        "offer_confirmed": {
          "offer": [
            2
          ]
        },
        "offer_created": {},
        "offer_declined": {
          "offer": [
            4,
            7
          ]
        },
        "trade_completed": {
          "history": [
            3,
            9
          ]
        },
        "trade_reversed": {
          "history": [
            4,
            5,
            6,
            7,
            11,
            12
          ]
        }
      }
    }
""".trimIndent()
