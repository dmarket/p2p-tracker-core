// Public DTO types for the `client-wasm` prover façade.
//
// These are declared *locally* (not re-exported from the generated
// `pkg/client_wasm.d.ts`) so the emitted `dist/*.d.ts` is self-contained: a
// value or type re-export `from "client-wasm"` would ship a dangling module
// reference no consumer can resolve (the pkg is unpublished — branch journal
// entry 21 measured exactly that defect in the previous revision's dist).
//
// The single source of truth is still the Rust side (`client-wasm/src/io.rs`
// `typescript_custom_section` and `client-wasm/src/types.rs`): `wasm-compat.ts`
// asserts, at compile time, that every type here stays mutually assignable
// with its generated counterpart, so drift fails `npm run typecheck` instead
// of surfacing at runtime.
//
// Where a type here deliberately *differs* from the generated one, it is a
// façade conversion `createProver` performs (bytes as `Uint8Array` instead of
// tsify's `number[]` rendering of `Vec<u8>`; camelCase instead of the one
// snake_case wart; truly-optional instead of required-but-nullable). Those
// types are checked one-directionally or not at all — see `wasm-compat.ts`.
export {};
//# sourceMappingURL=types.js.map