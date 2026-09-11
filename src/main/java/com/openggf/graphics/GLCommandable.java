package com.openggf.graphics;

public interface GLCommandable {
	void execute(int cameraX, int cameraY, int cameraWidth, int cameraHeight);

	/**
	 * Releases frame-owned resources when a queued command is drained without a
	 * successful execution. Implementations backed by pools must make this hook
	 * idempotent because an executing command may release itself in a
	 * {@code finally} block before its exception reaches the queue.
	 */
	default void discard() {
		// Most commands own no pooled frame state.
	}

	/**
	 * Unwinds a command that remained queued after an earlier command failed.
	 * Ordinary commands are discarded; commands that close an already-open GL
	 * scope may override this to restore that scope before releasing themselves.
	 */
	default void unwindAfterFailure(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
		discard();
	}
}
