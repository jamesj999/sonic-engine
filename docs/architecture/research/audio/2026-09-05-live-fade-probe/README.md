# Live fade listening stimulus

`AizFadeListeningProbe.java` uses the public AudioManager presentation and
capture path. It plays Knuckles music, requests a fade at four seconds, then
requests AIZ1 music at nine seconds. It produces eleven seconds of stereo
48 kHz PCM. This is a controlled listening stimulus, not a full-game trace or
a reproduction of the cutscene's exact timing.

Compile against each checkout's own classes and dependencies, and keep ROM
paths absolute. The dependency JAR below is produced by `mvn package`:

```sh
javac -cp target/classes:target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar -d target/fade-listening docs/architecture/research/audio/2026-09-05-live-fade-probe/AizFadeListeningProbe.java
java -cp target/fade-listening:target/classes:target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar AizFadeListeningProbe <absolute-s3k-rom> <research-root>/fade.wav
```

On Windows use `;` for the classpath separator. Keep generated audio outside
the repository: it contains user-ROM assets. Compare clips built from the
baseline and final candidate; producing a clip does not constitute listening
approval. The [boundary audit](../../../audits/audio/2026-09-05-live-parity-boundary-audit.md)
records the source findings and automated evidence.
