# `mcadapter` — the version-isolation boundary

Everything in this package (and its loader-side implementations) exists so that the
rest of ClientDevBridge does **not** have to change when Minecraft does.

## The rule (plan §7)

> All version-sensitive Minecraft internals live in `mcadapter/`; the protocol layer,
> handlers, and CLI must not contain per-MC-version logic.

Concretely, the following belong here and nowhere else:

- framebuffer / `NativeImage` capture and PNG encoding
- the end-of-frame and client-tick hooks
- widget-tree walking and per-widget-type snapshot extraction
- input dispatch (mouse, keyboard, key mappings)
- world creation, loading, deletion, and the integrated-server command dispatcher
- quickplay and window/`GuiScale` handling

Everything else — `net/` (the WebSocket transport), `protocol/` (JSON-RPC),
`handler/` (the method implementations), and the entire `clientdevbridge-cli`
TypeScript package — must compile and behave identically on every branch.

## How to port a branch

Porting ClientDevBridge to a new Minecraft version should mean porting this package
and the mixins, and nothing else. If a port needs a change outside `mcadapter/`,
mixins, or build files, that is a bug in the adapter: widen the interface on the
**oldest** affected branch first, then upmerge along
`master-1.21-lts` → `master-26-lts` → `master-26`.

Enforce this in review.

## Shape

`McAdapter` is the single entry point. `loader-fabric` and `loader-neoforge` each
install their own implementation of the loader-sensitive hooks during client setup;
everything that can be done with plain `net.minecraft` classes is implemented once
here in `loader-common`.

## Looking up names

Class and method names differ between branches. Do not guess them from memory —
inspect the branch's own decompiled/merged Minecraft jar. See `AGENTS.md` for where
the build puts it on this branch.
