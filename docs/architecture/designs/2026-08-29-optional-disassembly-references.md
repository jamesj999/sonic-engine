# Optional disassembly references

The Sonic Retro disassemblies are pinned development references, not OpenGGF build,
test, runtime, or release dependencies. Keeping them as Git submodules preserves an
exact upstream revision and lets GitHub expose each external repository at its
canonical `docs/` path, while Git's normal non-recursive clone keeps them absent.

The default setup path must therefore use an ordinary `git clone`. Contributors who
need assembly source for parity work may opt in with `git submodule update --init`.
CI and release workflows must continue using non-recursive checkout, and repository
guidance must say explicitly that Maven, engine startup, and ordinary tests do not
require the submodules.

This boundary does not permit runtime asset reads from the disassemblies. They remain
research inputs for labels, source citations, and ROM-offset discovery; runtime bytes
continue to come only from user-supplied ROMs.

The same review will inventory standalone development tooling that may deserve an
OpenGGF-org repository. That inventory is an audit and recommendation only: no tool is
moved as part of this change.
