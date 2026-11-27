# ADR-0008: Zero-dependency, pure-reducer hot path in the shell

- Status: Accepted · 2026-11-27
- Context: Every millisecond on the keystroke path is user-visible (NFR-P-02/03), and the `/ime` JS must
  parse fast on low-end devices (NFR-P-14).
- Decision:
  - `@ak/keyboard-engine` is a pure TypeScript package with **no dependencies**: layouts as data, a
    `reduce(state, event, editor) → {state, effects}` function, and a predictor interface.
  - `@ak/protocol` is types and constants only.
  - The shell uses a tiny `useSyncExternalStore` store (no state library), CSS Modules + CSS variables
    (no CSS-in-JS runtime), inline SVG icons (no icon font), and system fonts (no web-font payload).
  - Pressed-key visuals are applied imperatively; React renders only on layer, shift or theme change.
- Consequences: (+) Deterministic, exhaustively unit-testable input logic; tiny bundle; replayable bug
  reports (event logs). (−) We write a few small utilities ourselves instead of importing them.
