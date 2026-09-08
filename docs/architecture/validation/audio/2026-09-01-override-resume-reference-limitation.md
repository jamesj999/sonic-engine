# Override-resume reference authority limitation

**Date:** 2026-09-01
**Task:** sound-driver roadmap Task 8
**Terminal status:** `REFERENCE_LIMITATION`
**Limitation code:** `FRESH_AUTHENTICATED_NATIVE_GPGX_AUTHORITY_UNAVAILABLE`

## Decision

Task 8 stopped before fixture publication and before any Java production change. The two
TraceChaser producer milestones (`912fef0a` and `e3fdf73`) establish observation and
no-replace staging mechanics, but they do not confer reference authority. No override-resume
fixture was created or replaced, and no write or PCM expectation was inferred from a trace,
snapshot, or existing fixture.

The hard gate cannot be closed in the current environment:

1. The required current-session two-build observer reproduction gate has no configured
   `OPENGGF_GPGX_OBSERVER_SOURCE`, `_TOOLCHAIN_A`, `_TOOLCHAIN_B`, or `_STOCK` inputs and
   reports a skip rather than authority.
2. Even after removing the unrelated ambient `GIT_PAGER`, the locked recipe verifier fails
   closed with `secure-runtime: trust-root executable differs: /usr/bin/ar`. The recipe pins
   `/usr/bin/ar` to
   `69c93ee96fe89de9a071010905786a48c136fbabcdafff2fbd5bc4f2d7866f84`; the current host
   provides `0c1aceed56dde02eeed19228a4ba712f5e5d571bc7efb84f94960dd191b66656`.
3. The reviewed-capability guard also rejects the current source/capability pair. It hashes
   `CompleteRunAudioObserver.cs` as
   `d2ec919c783f3c3dc2a8c11ba2c167fd9975ca11dec9d78b10c8a3a4efb0ec72`, while the pinned
   capability records
   `e0be4819556cf74b82273cba7b748ddd13529f2dcc61029a302f4b0f8acfda89`. Task 8 permits
   refreshing only `task8_harness_executable_sha256`; changing this source field would break
   the pinned normalized capability-template identity and is not an authorised repair.
4. The stock BizHawk 2.11 tree fails installation authentication because
   `gpgx-audio-observer-source/identity.json` is absent. A pre-existing 2026-08-30 observer
   install passes the static identity test, but it was not freshly reproduced twice for this
   task and therefore remains assertion-only. The older 2026-08-10 install is quarantined and
   has a different identity hash.

The milestone's provenance inventory is also intentionally not represented as complete.
The draft attestation/metadata schemas do not yet carry all mandatory Task 8 fields: ROM
CRC-32; canonical BK2 relative path; TraceChaser gitlink; ProbeRuntime, probe, Lua contract,
producer, and service-manifest paths and hashes; BizHawk/core/adapter/Waterbox hashes;
capability-template and installed observer evidence; disassembly commits/routine citations;
the duplicated boundary/write/PCM inventory; normalization inventory; and exact capture,
validation, and publication commands. Consequently the closed publisher was tested only with
synthetic inputs and was not invoked against a production capture.

## Evidence

- S1 capture producer: 57 passed, 0 failed, 2 opt-in live-test skips.
- S2 capture producer and sink: 14 passed, 0 failed.
- Extractor/publisher: 6 passed, 0 failed.
- Closed CLI/launcher checks: 4 passed, 0 failed.
- Lua 5.4 contract suite: 10 passed, 0 failed.
- Two-copy deterministic build: executable SHA-256
  `0ba71e5c4f3dee9360b13c0c312a1dc21a61a5b4760ad3d15e02a9472179c8d3`;
  stale executable capability rejected, refreshed copy accepted;
  `DETERMINISTIC_BIZHAWK_HEADLESS_BUILD_OK`.
- Final host-visible process scan: zero task-owned `mono` or `EmuHawk` processes.

## Required continuation

A later task must first restore the exact locked observer build trust roots and supply two
fresh-copy reproduction inputs, reconcile the capability source identity without weakening the
pinned-template rule, and complete the mandatory provenance schemas/validator. Only then may it
run the two serial S1 captures, two serial S2 native captures, two ProbeRuntime diagnostics,
duplicate equality checks, and the first dedicated-bundle no-replace publication. Java transition
or resume behaviour remains hard-blocked until all of those steps succeed.

## Truthful Java gate naming — 2026-09-05

`TestS1OverrideResumeAudioOracle.authenticatedOverrideResumeReferenceIsUnavailable`
names the executable guarantee that exists today. It opens the fail-closed bundle reader and
requires the exact `FRESH_AUTHENTICATED_NATIVE_GPGX_AUTHORITY_UNAVAILABLE` limitation code. It does
not compare a resumed service or PCM packet, and its green result is not evidence that OpenGGF's
override/restore output matches the retail driver.

The highest-risk missing S1 gate remains the extra-life restore at `Sound_PlayBGM`
(`docs/s1disasm/s1.sounddriver.asm:754`) and `cfFadeInToPrevious` (`:2166`). In the shipped
`FixBugs=0` branch, the latter restores the saved driver region but omits the conditional YM2612
`$2B=$00` DAC-disable write. An authenticated comparison must therefore cover the source-ordered
first resumed service and the immediately following native PCM packet; a negative control that
invents `$2B=$00` must fail. Runtime changes remain unjustified until that evidence identifies an
actual divergence.

The S3K frontier tests already distinguish these outcomes: the full oracle method says it pins the
next mismatch frontier, while methods containing `Matches` limit their claims to an explicit
service prefix or write count. They require no reporting rename.

## Independent mechanics review — 2026-09-02

TraceChaser commit `fa69e177328f35f55330637ddfc5c7a24dde839f` corrects three
independent review findings while preserving this limitation:

1. The extractor now enforces every required member, rejects every unknown member, and validates
   the complete types, ranges, identities, and relationships for raw metadata, the selected S1/S2
   boundary, PCM, each selected write, and both emitted closed contracts.
2. Attestations now have one fixed-order compact UTF-8 serialization with no BOM and exactly one
   LF. Duplicate comparison clones the authenticated bytes and replaces only the 20-byte UTC
   timestamp value; whitespace, member order, newline count, malformed/multiple records, and all
   other byte differences are rejected.
3. The publisher rejects symlinked `fixtureRoot/s1` and `fixtureRoot/s2` intermediates. Multi-file
   rollback keeps an `O_NOFOLLOW` directory handle and staged device/inode/type authority, moves a
   public final to a private no-replace quarantine, and unlinks only when that identity proves the
   entry is publisher-owned. Unsupported native operations fail before the first link.

The executable review evidence was 15/15 extractor tests and 21/21 publisher-filter tests. The
policy tests were 31/31, with exact registration of only the three planned small JSON schemas and
continued rejection of an adjacent unlisted `contracts/audio/*.json`; both repository and reachable
history scanners pass. All authorised test-run process inventories were empty before and after, and
no BizHawk, EmuHawk, capture, fixture, or Java work occurred. These stronger mechanics do not supply
the missing fresh authenticated native-GPGX observation or provenance, so the terminal status and
required continuation above are unchanged.

## TOCTOU review hardening round 2 — 2026-09-02

TraceChaser commit `a61450ee10d2a76fd32edfe5c29791404bfe2b20` hardened two
previously identified race windows in the mechanics described above:

1. Closed four-file staging now opens the fixture root once, creates and traverses child directories
   with fd-relative `mkdirat` / `openat(O_DIRECTORY|O_NOFOLLOW)`, writes and fsyncs every temporary
   through the resulting directory fd, and retains the root plus final-directory fds through
   `linkat`. Immediately before the final link it traverses the relative directory again from the
   anchored root and requires the original directory device, inode, and type. Moving a validated
   `s1` or `s2` directory and replacing it with a symlink before that revalidation therefore fails;
   staging and the fresh traversal do not follow the replacement path.
2. Rollback no longer makes a check-then-unlink authority claim. It creates a mode-0700 sibling
   quarantine and moves a public final there with fd-relative
   `renameat2(RENAME_NOREPLACE)`. A mismatched entry is restored without replacement; an owned or
   ownership-uncertain entry remains in quarantine as failure evidence. This deliberately bounded
   residue is required because Linux provides no atomic unlink-if-this-name-still-has-this-inode
   operation. The publisher never deletes a competitor substituted after identity verification.

The deterministic adversarial tests passed 1/1 for a validated-directory swap before link and 1/1
for quarantine replacement after identity verification. The aggregate publisher/no-replace/closed
CLI filter passed 24/24, the accepted extractor/schema filter remained 15/15, and the exact policy
suite remained 31/31. Both mandatory scanners, the launcher syntax check, all three schema JSON
checks, and parent/submodule diff checks passed. Every authorised C# run had empty pre/post
host-visible Mono/EmuHawk/BizHawk PID inventories. No emulator, capture, fixture, or Java work was
started, so this correction still does not alter the terminal limitation or continuation gate.

## Atomic publication contract correction — 2026-09-02

A later review found that the preceding round's conclusion was too broad. Commit `a61450ee` does
materially harden fd-relative staging, detects a directory replacement completed before its final
identity check, and avoids deleting an identity-uncertain competitor during quarantine. It does not
prove either nested-path containment or four-name atomic publication:

1. After the fresh traversal and identity check succeed but before `linkat`, a same-credential actor
   with rename authority can move the retained target directory outside `fixtureRoot`. The retained
   fd still names that moved inode, so `linkat` can make the final visible in the moved-old directory.
   A later `statx` check merely moves this race window; `openat2` constrains one lookup and does not
   pin the resulting inode to its original pathname.
2. Four successful `linkat` calls have four distinct visibility points. Readers can observe a
   nonempty proper subset, and a later quarantine or rollback cannot make those earlier observations
   atomic. The safe quarantine rule prevents competitor deletion but cannot turn sequential links
   into one transaction.

The prior plan and mechanics review claimed no partial final and rejection of directory/link races
without declaring cooperative writers or namespace-stable ancestors. The counterexample is therefore
valid against that stated contract rather than an unrelated stronger model. The replacement design's
environmental precondition below is an explicit contract amendment; it was not enforced by
`a61450ee`.

The canonical Task 8 plan therefore retracts the four-file transaction claim. Publication must be
redesigned as one absent dedicated bundle directory, fully built and fsynced under a private sibling
name and committed with one `renameat2(RENAME_NOREPLACE)` followed by fixture-root `fsync`. The one
successful rename is the only visibility linearization point; existing commits are untouched and
before that rename the publisher creates or modifies no public name. A precommit failure may leave
private residue; the target stays absent only if no other actor creates it, and any competitor remains
untouched. A post-rename root-`fsync` failure leaves a complete, never-rolled-back bundle with the
distinct `committed but durability unconfirmed` result.

That replacement contract also states its necessary environmental precondition. Supported publishers
cooperate in an exclusive fixture-root lock, and the authoritative root and ancestors remain protected
and namespace-stable. Linux cannot stop a hostile same-credential actor with rename or mount authority
from moving the root, an ancestor, or the committed bundle after any successful syscall; that stronger
threat is explicitly unsupported rather than presented as solved by revalidation. The bundle publisher,
its deterministic race/fault tests, and bundle-aware Java consumers must be implemented and reviewed
before any publication. No capture, fixture, or Java change occurred here, and
`REFERENCE_LIMITATION` remains unchanged.

## Atomic bundle mechanics implementation — 2026-09-02

The retracted four-leaf transaction has now been replaced in the pinned
TraceChaser producer by the accepted one-directory commit protocol. The
publisher opens the repository, TraceChaser submodule, and fixed consumer
parity root beneath a retained `/` anchor with
`openat2(RESOLVE_BENEATH|RESOLVE_NO_SYMLINKS)`, locks the retained root fd,
constructs a private mode-0700 sibling with exact mode-0700 `s1`/`s2`
directories and four mode-0600 leaves, and uses only fd-relative creation,
writes, reopen validation, enumeration, and fsync. It records and revalidates
root and staged directory device/inode/type/mount identities. The only public
operation is one `renameat2(RENAME_NOREPLACE)` of the complete bundle, followed
by root fsync. It performs no public rollback: a post-rename root-fsync failure
returns the distinct committed-but-durability-unconfirmed result and leaves the
complete bundle visible; precommit failure may leave only a private mode-0700
residue. An existing or racing competitor remains untouched.

The closed metadata contract now identifies the fixed bundle-relative root,
exact four-member inventory, `linux-atomic-bundle-rename-noreplace-v1`
protocol, and cooperative-lock/namespace-stability precondition. The Java
consumer opens only that bundle commit object through secure directory streams,
rejects missing, extra, symlinked, non-regular, wrong-schema, wrong-inventory,
or digest/count-mismatched members, and has no legacy leaf fallback. The named
S1 and S2 oracle tests continue to terminate with
`FRESH_AUTHENTICATED_NATIVE_GPGX_AUTHORITY_UNAVAILABLE` while the bundle is
absent.

Deterministic native tests inject every recorded precommit syscall ordinal,
model non-`EEXIST` rename failure and post-rename root-fsync failure, serialize
two cooperating publishers, replace trusted/staged directories before final
revalidation, race a competitor at the rename boundary, observe both sides of
the visibility barrier, and kill child publishers immediately before and after
commit. A separate adversarial test moves the retained fixture root after the
last revalidation and before `renameat2`; the commit then appears in the moved
old root rather than the replacement path. That outcome is executable evidence
for the declared unsupported same-credential namespace mutation, not a claim of
containment. The implementation tests create only synthetic scratch bundles. No
BizHawk, EmuHawk, GPGX capture, raw evidence, canonical fixture, comparator
authority, or Java production behavior was started or changed.

These mechanics remove the atomic-publication design blocker recorded above;
they do not supply the missing fresh native-GPGX authority, complete provenance
inventory, duplicate captures, or authenticated S1/S2 expectations. The
terminal status therefore remains `REFERENCE_LIMITATION`, code
`FRESH_AUTHENTICATED_NATIVE_GPGX_AUTHORITY_UNAVAILABLE`.

Implementation verification was 28/28 extractor/atomic-publisher tests and
34/34 broader publisher/closed-CLI tests, with empty host-visible
Mono/EmuHawk/BizHawk/GPGX process inventories before and after every authorised
Mono run. The repository and reachable-history scanners both passed; their
policy unit suite passed 31/31; the launcher passed `bash -n`; and all three
closed schemas parsed with `jq`. The Java commit-object consumer and named
limitation oracles passed 6/6. Fresh-JVM guards passed 12/12 TraceChaser-boundary,
69/69 architectural-source, and 96/96 build-tooling tests after the exact
non-floating gitlink pins were updated to the committed producer revision.

## Independent review remediation — 2026-09-02

TraceChaser commit `98b4afaf91bb799a6b79aee22625b14c33113e66`
closes three further independent-review gaps without changing publication
authority. The reference schema now discriminates at the top level by game:
S1 has its exact boundary and requires PCM `type: native_pcm_packet`, while S2
has its distinct exact boundary and forbids that member. Cross-game boundary
and PCM shapes and the missing/extra `type` cases are executable negatives.

The Java commit-object reader now performs the same complete fail-closed
validation as the producer: duplicate JSON keys are rejected; every metadata,
boundary, write, and PCM member has an exact inventory and strict node type;
numeric ranges, write order, PCM timing/size/digest relationships, raw byte
count, and the literal namespace precondition are checked without coercion.
The synthetic reader tests contain complete valid S1 and S2 contracts rather
than empty nested objects.

Finally, `renameat2` and every `fsync` return their result and captured `errno`
through an injectable native adapter. Tests now execute the actual non-`EEXIST`
rename failure branch and the post-rename root-fsync result branch instead of
throwing before either call site. The after-last-revalidation root-move test
observes publication through the retained moved-root fd and absence at the
replacement configured pathname, explicitly demonstrating why that
same-credential mutation remains outside the supported threat model.

Verification was 32/32 extractor/atomic-publisher tests and 36/36 broader
publisher/closed-CLI tests, with empty host-visible
Mono/EmuHawk/BizHawk/GPGX inventories before and after every authorised Mono
run. The 31/31 policy tests, both mandatory scanners, launcher syntax, and all
three schema parses passed. The strict Java reader plus both limitation oracles
passed 11/11; fresh-JVM guards passed 12/12 TraceChaser-boundary, 69/69
architectural-source, and 96/96 build-tooling tests. No emulator, capture, raw
evidence, canonical fixture, or comparator-authority action occurred, so
`REFERENCE_LIMITATION` and
`FRESH_AUTHENTICATED_NATIVE_GPGX_AUTHORITY_UNAVAILABLE` remain unchanged.
