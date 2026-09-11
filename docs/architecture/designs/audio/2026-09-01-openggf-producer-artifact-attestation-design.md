# OpenGGF complete-run producer artifact attestation

**Status:** accepted prerequisite; signing trust root not installed  
**Date:** 2026-09-01  
**Scope:** authenticated `OPENGGF` complete-run audio producers

## Decision

Keep every `OPENGGF` complete-run producer binding unavailable until a detached signed artifact
manifest and an independently provisioned verification key exist. The exact unavailable reason is:

> OpenGGF producer artifact attestation trust root is not installed

The monolithic executable JAR cannot contain an authoritative hash of itself. A runtime-computed
hash is also not a trust root: modified code would merely authenticate its own modified bytes.
Build version text, an ambient Git checkout, an unsigned release checksum, or a public key carried
only inside the measured JAR have the same self-trust problem.

This decision does not invalidate the v2 engine reducer. It prevents that reducer from being
published as an authenticated fixed producer until the executable that owns the capture has an
identity independent of itself.

## Threat model

The future contract must reject an altered, substituted, stale, or wrong OpenGGF producer
artifact; an altered manifest or signature; a signer outside the accepted key policy; a manifest
for the wrong profile or producer kind; and replacement between review and process startup.

It does not claim to protect against a compromised authorized signer, release account, CI system,
operating system, JVM, or trusted verifier. ROM, BK2, run-manifest, observer, and semantic evidence
remain subject to their existing independent gates.

## Required detached contract

The release process builds and tests the exact universal JAR, then emits canonical UTF-8 JSON with
this closed shape:

```text
schema: openggf-producer-attestation.v1
subject: name, size, sha256
producer: kind=OPENGGF, exact allowed profile IDs, runtimeArtifact=OPENGGF_PRODUCER
build: source repository, 40-hex commit, dirty=false, JDK major=21
validity: not-before, not-after
signer: algorithm=Ed25519, key ID
```

The signature is detached from both the JAR and manifest. The accepted public key, key ID, and
revocation policy are provisioned through an independently authenticated offline trust bundle or
installer channel. A key shipped only by the release it authenticates is not independent.

Before the producer JVM starts, a trusted launcher must:

1. reject linked, special, non-canonical, missing, or mutable input shapes;
2. strictly parse bounded canonical manifest bytes;
3. verify signature, key status, validity interval, producer kind, and profile authorization;
4. measure the exact JAR size and SHA-256;
5. copy the verified JAR, manifest, and signature into a private create-new staging directory;
6. revalidate the private copy and launch that exact JAR path.

The child JVM repeats the size and digest comparison against the authenticated assertion before
profile loading, ROM/BK2/manifest access, engine startup, or output creation. This second check is
TOCTOU defence; it does not replace the external trust root.

## Runtime and profile binding

The closed bootstrap creates an opaque verified-attestation handle. Callers cannot provide hashes,
keys, signer IDs, or expected identities through `CompleteRunAudioProducer.Request`.

The authenticated assertion supplies the exact immutable `ProducerRuntimeIdentity`, including
`RuntimeArtifact.OPENGGF_PRODUCER`. Capture metadata also binds the manifest SHA-256 and signer key
ID. Store validation checks those values before accepting any records.

Do not mutate a registered profile or fill identity maps after startup. Before installing the
first v2 store, introduce a frozen attested-binding shape that names the attestation policy and
allowed producer/profile tuple. If persisted v2 stores exist by then, version the canonical schema
instead of changing the meaning of `PinnedProducerBinding` in place.

Direct `produce OPENGGF` without the verified pre-launch channel remains unavailable. Pair-wide
`producer-status` continues to describe the full reference/engine parity route. The independent
`producer-kind-status <kind> <profile>` command may report one reference producer available while
the engine trust root remains absent, or vice versa.

## Release prerequisite

Before enabling a binding, establish:

- an actual signing authority with documented custody, rotation, and revocation;
- an independently distributed trust-root fingerprint or bundle;
- canonical manifest generation after the final tested artifact exists;
- publication of the JAR, manifest, and detached signature as one release set;
- deterministic build controls before making any reproducible-build claim.

Key rotation uses explicit, non-overlapping key IDs in the external bundle. A release signed by a
new key cannot introduce that key as its sole authority.

## Required verification

Tests must cover valid signed input and one-byte mutations; size/hash mismatch; manifest/signature
mutation; unknown, revoked, expired, and not-yet-valid keys; wrong producer/profile; duplicate or
unknown JSON fields; non-canonical encodings; linked/special files; staged-copy replacement;
direct-Java bypass; and proof that verification precedes every ROM, BK2, output, and engine access.

Test-only keys must use an unmistakably test-only policy and can never make a production profile
available. Guards must reject literal, zero, self-derived, ambient-Git, or caller-provided OpenGGF
artifact authority and must keep `OPENGGF` unavailable until a production signing policy is
installed.

After the real trust root exists, enable one profile at a time, run fresh-JVM preflight and direct
producer/store validation, require two byte-identical captures, and only then permit pair-wide
parity orchestration.

## Rejected alternatives

- whole-JAR hash embedded in that JAR: circular;
- runtime self-hash copied into metadata: dynamic self-trust;
- embedded producer-closure digest: modified verifier code can redefine the closure;
- unsigned detached checksum or GitHub release: integrity without independent authority;
- version properties or Git commit text: self-asserted provenance;
- ambient Git checkout: mutable and the wrong authority;
- signature whose only public key is inside the measured JAR: self-trust under another name.
