// Compile-time drift alarm: asserts the locally-declared types in this
// package against the generated `pkg/client_wasm.d.ts` (resolved through the
// tsconfig `paths` alias — build `../pkg` first, see README).
//
// The package declares its DTOs locally so the emitted `dist/*.d.ts` is
// self-contained (a `from "client-wasm"` re-export would dangle for every
// consumer — journal 21). The single source of truth stays the Rust side;
// this file is what enforces it: if `client-wasm/src/{io,types,log}.rs`
// changes shape, `npm run typecheck` fails here instead of a caller failing
// at runtime.
//
// Everything below is type-level only — nothing survives into dist (the
// emitted .js is empty and the .d.ts is `export {};`).
export {};
//# sourceMappingURL=wasm-compat.js.map