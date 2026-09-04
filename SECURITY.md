# Security Policy

This library handles Steam session credentials and creates/cancels real Steam trade offers, so we take
security seriously and welcome reports.

## Reporting a vulnerability

**Please do not report security issues through public GitHub issues, pull requests, or discussions.**

Instead, use GitHub's private vulnerability reporting:

1. Go to the repository's **Security** tab.
2. Click **Report a vulnerability** (under *Advisories*).
3. Fill in the form with the details below.

This opens a private advisory visible only to you and the maintainers.

Please include, where possible:

- A description of the issue and its impact.
- The affected version(s) or commit.
- Steps to reproduce, or a proof of concept.
- Any suggested remediation.

We will acknowledge your report, keep you updated on our assessment, and coordinate a fix and
disclosure timeline with you. Please give us a reasonable opportunity to address the issue before any
public disclosure.

## Supported versions

This project is pre-1.0 and moves fast. Security fixes are made against the **latest released version**
only; there are no back-ports to older lines. Always upgrade to the newest release before reporting.

## Security model & scope

The library is intentionally constrained. The following boundaries are enforced by design, and reports
demonstrating that any of them can be crossed are especially valuable:

- **The Steam credential is device-only.** It lives behind the Steam-facing code paths and is never
  transmitted to the DMarket backend. No backend-facing API accepts it.
- **Only two Steam write actions exist: create and cancel a trade offer.** The library never confirms
  or accepts trades.
- **No Steam Guard / mobile-authenticator handling.** The library never generates authenticator codes,
  never reads the identity/shared secrets, and never calls Steam mobile-confirmation endpoints. The
  user confirms trades themselves in the official Steam mobile app.
- **The library never annotates or acts on non-DMarket trade offers.**

Because this is an open-source core, anyone can verify these properties in the source. Findings that
show a gap between these stated guarantees and the actual behavior are in scope and encouraged.
