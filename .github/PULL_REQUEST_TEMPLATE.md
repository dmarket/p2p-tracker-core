<!--
Thanks for contributing! Please keep the PR focused and fill out the sections below.
See CONTRIBUTING.md for build, style, and release conventions.
-->

## Summary

<!-- What does this change do, and why? -->

## Type of change

<!-- Check all that apply. The commit type drives the release version bump (see CONTRIBUTING.md). -->

- [ ] `fix` — bug fix (patch)
- [ ] `feat` — new feature (minor)
- [ ] Breaking change (`feat!` / `BREAKING CHANGE:`) (major)
- [ ] `docs` / `ci` / `refactor` / `test` / `chore` (no user-facing behavior change)

## Related issues

<!-- e.g. Closes #123 -->

## How was this tested?

<!-- Commands run, cases covered. New/changed :domain logic should come with tests. -->

- [ ] `./gradlew check` passes locally

## Checklist

- [ ] I ran `./gradlew spotlessApply` (code is formatted)
- [ ] New/changed logic in `:domain` has `commonTest` coverage, and `:domain` stays IO-free
- [ ] The `@JsExport` surface was kept thin (no new public API leaked unintentionally)
- [ ] Updated `CHANGELOG.md` under `## [Unreleased]` if this is user-facing
- [ ] No secrets, tokens, or credentials are included in the diff
