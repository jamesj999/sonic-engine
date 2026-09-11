package com.openggf.graphics;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * Groups multiple GLCommands together for batch rendering.
 * Uses modern OpenGL (VAO/VBO) for core profile compatibility.
 */
public class GLCommandGroup implements GLCommandable {
	private final int drawMethod;
	private final List<GLCommand> commands;

	// Vertex size: 2 floats position + 4 floats color = 6 floats
	private static final int VERTEX_SIZE = 6;
	private static FloatBuffer batchBuffer;

	static boolean hasNativeScratch() {
		return batchBuffer != null;
	}

	public GLCommandGroup(int drawMethod, List<GLCommand> commands) {
		this.drawMethod = drawMethod;
		this.commands = List.copyOf(commands);
	}

	public void execute(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
		if (commands.isEmpty()) {
			return;
		}

		// Get blend mode from first command
		GLCommand.BlendType blendMode = commands.get(0).getBlendMode();

		// Ensure buffer is large enough
		int requiredFloats = commands.size() * VERTEX_SIZE;
		ensureNativeScratch(requiredFloats);

		// Batch all vertices into a single buffer
		batchBuffer.clear();
		int vertexCount = 0;

		GLCommand.setInGroup(true);
		try {
			for (GLCommand command : commands) {
				// Only process VERTEX2I commands (the typical case for groups)
				if (command.getCommandType() == GLCommand.CommandType.VERTEX2I) {
					float x = command.getX1() - cameraX;
					float y = command.getY1() + cameraY;
					float r = command.getColour1();
					float g = command.getColour2();
					float b = command.getColour3();
					float a = command.getAlpha();

					batchBuffer.put(x).put(y).put(r).put(g).put(b).put(a);
					vertexCount++;
				}
			}
		} finally {
			GLCommand.setInGroup(false);
		}

		batchBuffer.flip();

		// Draw all vertices at once
		GLCommand.drawBatch(drawMethod, batchBuffer, vertexCount, cameraX, cameraY, blendMode);
	}

	/**
	 * Cleanup static resources.
	 */
	public static void cleanup() {
		if (batchBuffer != null) {
			MemoryUtil.memFree(batchBuffer);
			batchBuffer = null;
		}
	}

	static void ensureNativeScratch(int requiredFloats) {
		if (batchBuffer == null || batchBuffer.capacity() < requiredFloats) {
			if (batchBuffer != null) {
				MemoryUtil.memFree(batchBuffer);
			}
			batchBuffer = MemoryUtil.memAllocFloat(Math.max(requiredFloats, 256));
		}
	}
}
