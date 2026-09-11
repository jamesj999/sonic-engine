package com.openggf.graphics;

import org.lwjgl.system.MemoryUtil;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * OpenGL command for drawing primitives (rectangles, lines) using modern OpenGL.
 * Uses VAO/VBO with the debug color shader for core profile compatibility.
 */
public class GLCommand implements GLCommandable {
	private static boolean inGroup = false;

	public enum CommandType {
		RECTI, VERTEX2I, USE_PROGRAM, ENABLE, DISABLE, CUSTOM;
	}

	public enum BlendType {
		SOLID, ONE_MINUS_SRC_ALPHA, SUBTRACTIVE
	}

	public static void setInGroup(boolean inGroup) {
		GLCommand.inGroup = inGroup;
	}

	public static boolean isInGroup() {
		return inGroup;
	}

	public BlendType getBlendMode() {
		return blendMode;
	}

	private final BlendType defaultBlendMode = BlendType.SOLID;

	private CommandType glCmdCommandType;
	private int drawMethod;
	private BlendType blendMode;
	private float colour1;
	private float colour2;
	private float colour3;
	private float alpha = 1.0f;
	private int x1;
	private int y1;
	private int x2;
	private int y2;
	private int value;
	private GLCommandable customAction;
	private final GraphicsManager graphicsManager;
	private final int screenHeightPixels;

	// Static VAO/VBO for primitive rendering (shared across all instances)
	private static int vaoId = 0;
	private static int vboId = 0;
	private static FloatBuffer vertexBuffer;
	private static final int VERTEX_SIZE = 6; // 2 floats position + 4 floats color
	private static final int MAX_VERTICES = 64;

	// Cached uniform locations
	private static int cachedProjectionLoc = -1;
	private static int cachedCameraOffsetLoc = -1;
	private static int lastProgramId = -1;

	static boolean hasNativeScratch() {
		return vertexBuffer != null;
	}

	public GLCommand(CommandType commandType, int value) {
		this(commandType, value, bootstrapGraphicsManager(), bootstrapConfigService());
	}

	private GLCommand(CommandType commandType, int value,
			GraphicsManager graphicsManager,
			SonicConfigurationService configService) {
		this.graphicsManager = graphicsManager;
		this.screenHeightPixels = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
		this.glCmdCommandType = commandType;
		this.value = value;
	}

	public GLCommand(CommandType commandType, GLCommandable customAction) {
		this(commandType, customAction, bootstrapGraphicsManager(), bootstrapConfigService());
	}

	private GLCommand(CommandType commandType, GLCommandable customAction,
			GraphicsManager graphicsManager,
			SonicConfigurationService configService) {
		this.graphicsManager = graphicsManager;
		this.screenHeightPixels = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
		this.glCmdCommandType = commandType;
		this.customAction = customAction;
	}

	/**
	 * A new GLCommand to add to the GraphicsManager's draw queue.
	 *
	 * @param glCmdCommandType
	 * @param drawMethod
	 * @param colour1
	 * @param colour2
	 * @param colour3
	 * @param x1
	 * @param y1
	 * @param x2
	 * @param y2
	 */
	public GLCommand(CommandType glCmdCommandType, int drawMethod, float colour1, float colour2,
			float colour3, int x1, int y1, int x2, int y2) {
		this(glCmdCommandType, drawMethod, colour1, colour2, colour3, x1, y1, x2, y2,
				bootstrapGraphicsManager(), bootstrapConfigService());
	}

	private GLCommand(CommandType glCmdCommandType, int drawMethod, float colour1, float colour2,
			float colour3, int x1, int y1, int x2, int y2,
			GraphicsManager graphicsManager, SonicConfigurationService configService) {
		this.graphicsManager = graphicsManager;
		this.screenHeightPixels = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
		this.glCmdCommandType = glCmdCommandType;
		this.drawMethod = drawMethod;
		this.colour1 = colour1;
		this.colour2 = colour2;
		this.colour3 = colour3;
		this.alpha = 1.0f;
		this.x1 = x1;
		this.y1 = screenHeightPixels - y1;
		this.x2 = x2;
		this.y2 = screenHeightPixels - y2;
		this.blendMode = defaultBlendMode;
	}

	public GLCommand(CommandType glCmdCommandType, int drawMethod, BlendType blendType, float colour1,
			float colour2,
			float colour3, int x1, int y1, int x2, int y2) {
		this(glCmdCommandType, drawMethod, blendType, colour1, colour2, colour3, x1, y1, x2, y2,
				bootstrapGraphicsManager(), bootstrapConfigService());
	}

	private GLCommand(CommandType glCmdCommandType, int drawMethod, BlendType blendType, float colour1,
			float colour2,
			float colour3, int x1, int y1, int x2, int y2,
			GraphicsManager graphicsManager, SonicConfigurationService configService) {
		this.graphicsManager = graphicsManager;
		this.screenHeightPixels = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
		this.glCmdCommandType = glCmdCommandType;
		this.drawMethod = drawMethod;
		this.colour1 = colour1;
		this.colour2 = colour2;
		this.colour3 = colour3;
		this.alpha = 1.0f;
		this.x1 = x1;
		this.y1 = screenHeightPixels - y1;
		this.x2 = x2;
		this.y2 = screenHeightPixels - y2;
		this.blendMode = blendType;
	}

	public GLCommand(CommandType glCmdCommandType, int drawMethod, BlendType blendType, float colour1,
			float colour2,
			float colour3, float alpha, int x1, int y1, int x2, int y2) {
		this(glCmdCommandType, drawMethod, blendType, colour1, colour2, colour3, alpha, x1, y1, x2, y2,
				bootstrapGraphicsManager(), bootstrapConfigService());
	}

	private GLCommand(CommandType glCmdCommandType, int drawMethod, BlendType blendType, float colour1,
			float colour2,
			float colour3, float alpha, int x1, int y1, int x2, int y2,
			GraphicsManager graphicsManager, SonicConfigurationService configService) {
		this.graphicsManager = graphicsManager;
		this.screenHeightPixels = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
		this.glCmdCommandType = glCmdCommandType;
		this.drawMethod = drawMethod;
		this.colour1 = colour1;
		this.colour2 = colour2;
		this.colour3 = colour3;
		this.alpha = alpha;
		this.x1 = x1;
		this.y1 = screenHeightPixels - y1;
		this.x2 = x2;
		this.y2 = screenHeightPixels - y2;
		this.blendMode = blendType;
	}

	// Accessor methods for GLCommandGroup batching
	public float getX1() { return x1; }
	public float getY1() { return y1; }
	public float getColour1() { return colour1; }
	public float getColour2() { return colour2; }
	public float getColour3() { return colour3; }
	public float getAlpha() { return alpha; }
	public CommandType getCommandType() { return glCmdCommandType; }

	private static void ensureBuffers() {
		if (vaoId == 0) {
			vaoId = glGenVertexArrays();
			vboId = glGenBuffers();
		}
		ensureNativeScratch();
	}

	static void ensureNativeScratch() {
		if (vertexBuffer == null) {
			vertexBuffer = MemoryUtil.memAllocFloat(MAX_VERTICES * VERTEX_SIZE);
		}
	}

	private static void setupShaderAndUniforms(int cameraX, int cameraY) {
		GraphicsManager gm = bootstrapGraphicsManager();
		ShaderProgram debugShader = gm.getDebugShaderProgram();
		if (debugShader == null) {
			return;
		}

		int programId = debugShader.getProgramId();
		glUseProgram(programId);

		// Cache uniform locations if program changed
		if (programId != lastProgramId) {
			cachedProjectionLoc = glGetUniformLocation(programId, "ProjectionMatrix");
			cachedCameraOffsetLoc = glGetUniformLocation(programId, "CameraOffset");
			lastProgramId = programId;
		}

		// Set projection matrix
		if (cachedProjectionLoc != -1) {
			float[] projMatrix = gm.getProjectionMatrixBuffer();
			if (projMatrix != null) {
				glUniformMatrix4fv(cachedProjectionLoc, false, projMatrix);
			}
		}

		// Set camera offset to zero - the offset is already baked into vertex positions
		if (cachedCameraOffsetLoc != -1) {
			glUniform2f(cachedCameraOffsetLoc, 0.0f, 0.0f);
		}
	}

	private static GraphicsManager bootstrapGraphicsManager() {
		return GameServices.graphics();
	}

	private static SonicConfigurationService bootstrapConfigService() {
		return GameServices.configuration();
	}

	public void execute(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
		switch (glCmdCommandType) {
			case USE_PROGRAM:
				glUseProgram(value);
				return;
			case ENABLE:
				glEnable(value);
				return;
			case DISABLE:
				glDisable(value);
				return;
			case CUSTOM:
				if (customAction != null) {
					customAction.execute(cameraX, cameraY, cameraWidth, cameraHeight);
				}
				return;
		}

		// Skip rendering when in a group - GLCommandGroup handles batch rendering
		if (isInGroup()) {
			return;
		}

		// Setup blending
		if (blendMode == BlendType.SOLID) {
			glDisable(GL_BLEND);
		} else if (blendMode == BlendType.ONE_MINUS_SRC_ALPHA) {
			glEnable(GL_BLEND);
			glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		} else if (blendMode == BlendType.SUBTRACTIVE) {
			// result = dst - src (matching FadeManager.renderBlackFade)
			glEnable(GL_BLEND);
			glBlendEquation(GL_FUNC_REVERSE_SUBTRACT);
			glBlendFunc(GL_ONE, GL_ONE);
		}

		ensureBuffers();
		setupShaderAndUniforms(cameraX, cameraY);

		if (CommandType.RECTI.equals(glCmdCommandType)) {
			// Draw filled rectangle using GL_TRIANGLE_FAN
			float rx1 = x1 - cameraX;
			float ry1 = y1 + cameraY;
			float rx2 = x2 - cameraX;
			float ry2 = y2 + cameraY;

			vertexBuffer.clear();
			// 4 vertices for rectangle (bottom-left, bottom-right, top-right, top-left)
			putVertex(rx1, ry1, colour1, colour2, colour3, alpha);
			putVertex(rx2, ry1, colour1, colour2, colour3, alpha);
			putVertex(rx2, ry2, colour1, colour2, colour3, alpha);
			putVertex(rx1, ry2, colour1, colour2, colour3, alpha);
			vertexBuffer.flip();

			drawVertices(GL_TRIANGLE_FAN, 4);

			// Restore additive blend equation after subtractive draw
			if (blendMode == BlendType.SUBTRACTIVE) {
				glBlendEquation(GL_FUNC_ADD);
			}
		} else if (CommandType.VERTEX2I.equals(glCmdCommandType)) {
			// Single vertex draw (usually used in groups, but handle standalone)
			float vx = x1 - cameraX;
			float vy = y1 + cameraY;

			vertexBuffer.clear();
			putVertex(vx, vy, colour1, colour2, colour3, alpha);
			vertexBuffer.flip();

			drawVertices(GL_POINTS, 1);
		}
	}

	private void putVertex(float x, float y, float r, float g, float b, float a) {
		vertexBuffer.put(x).put(y).put(r).put(g).put(b).put(a);
	}

	private void drawVertices(int drawMode, int vertexCount) {
		glBindVertexArray(vaoId);
		glBindBuffer(GL_ARRAY_BUFFER, vboId);
		glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_DYNAMIC_DRAW);

		// Position attribute (location 0)
		glVertexAttribPointer(0, 2, GL_FLOAT, false, VERTEX_SIZE * 4, 0L);
		glEnableVertexAttribArray(0);

		// Color attribute (location 1)
		glVertexAttribPointer(1, 4, GL_FLOAT, false, VERTEX_SIZE * 4, 2 * 4L);
		glEnableVertexAttribArray(1);

		glDrawArrays(drawMode, 0, vertexCount);

		glDisableVertexAttribArray(0);
		glDisableVertexAttribArray(1);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glBindVertexArray(0);
	}

	/**
	 * Static method to draw batched vertices (used by GLCommandGroup).
	 */
	public static void drawBatch(int drawMode, FloatBuffer vertices, int vertexCount,
			int cameraX, int cameraY, BlendType blendMode) {
		if (vertexCount <= 0) {
			return;
		}

		// Setup blending
		if (blendMode == BlendType.SOLID) {
			glDisable(GL_BLEND);
		} else {
			glEnable(GL_BLEND);
			glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		}

		ensureBuffers();
		setupShaderAndUniforms(cameraX, cameraY);

		glBindVertexArray(vaoId);
		glBindBuffer(GL_ARRAY_BUFFER, vboId);
		glBufferData(GL_ARRAY_BUFFER, vertices, GL_DYNAMIC_DRAW);

		// Position attribute (location 0)
		glVertexAttribPointer(0, 2, GL_FLOAT, false, VERTEX_SIZE * 4, 0L);
		glEnableVertexAttribArray(0);

		// Color attribute (location 1)
		glVertexAttribPointer(1, 4, GL_FLOAT, false, VERTEX_SIZE * 4, 2 * 4L);
		glEnableVertexAttribArray(1);

		glDrawArrays(drawMode, 0, vertexCount);

		glDisableVertexAttribArray(0);
		glDisableVertexAttribArray(1);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glBindVertexArray(0);
	}

	/**
	 * Cleanup static resources.
	 */
	public static void cleanup() {
		inGroup = false;
		if (vboId != 0) {
			glDeleteBuffers(vboId);
			vboId = 0;
		}
		if (vaoId != 0) {
			glDeleteVertexArrays(vaoId);
			vaoId = 0;
		}
		if (vertexBuffer != null) {
			MemoryUtil.memFree(vertexBuffer);
			vertexBuffer = null;
		}
		lastProgramId = -1;
		cachedProjectionLoc = -1;
		cachedCameraOffsetLoc = -1;
	}

	/** Resets native/static state without issuing GL calls. */
	public static void cleanupHeadless() {
		inGroup = false;
		vaoId = 0;
		vboId = 0;
		if (vertexBuffer != null) {
			MemoryUtil.memFree(vertexBuffer);
			vertexBuffer = null;
		}
		lastProgramId = -1;
		cachedProjectionLoc = -1;
		cachedCameraOffsetLoc = -1;
	}
}
