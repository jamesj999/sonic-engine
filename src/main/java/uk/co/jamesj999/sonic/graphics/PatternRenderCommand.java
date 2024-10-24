package uk.co.jamesj999.sonic.graphics;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.level.PatternDesc;

import java.nio.FloatBuffer;

public class PatternRenderCommand implements GLCommandable {

    private final int patternTextureId;
    private final int paletteTextureId;
    private final PatternDesc desc;
    private final int x;
    private final int y;

    public PatternRenderCommand(int patternTextureId, int paletteTextureId, PatternDesc desc, int x, int y) {
        this.patternTextureId = patternTextureId;
        this.paletteTextureId = paletteTextureId;
        this.desc = desc;
        this.x = x;
        this.y = SonicConfigurationService.getInstance().getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS) - y;
    }

    @Override
    public void execute(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        int translateX = x - cameraX;
        int translateY = y + cameraY;
        // Translate the pattern relative to the camera position
        GL11.glTranslatef(x - cameraX, y + cameraY, 0);

        // Apply horizontal and vertical flips
        if (desc.getHFlip()) {
            GL11.glScalef(-1, 1, 1);  // Horizontal flip
            GL11.glTranslatef(-8, 0, 0);  // Adjust for flipping
        }
        if (!desc.getVFlip()) {
            GL11.glScalef(1, -1, 1);  // Vertical flip
            GL11.glTranslatef(0, -8, 0);  // Adjust for flipping
        }

        // Bind the shader program
        ShaderProgram shaderProgram = GraphicsManager.getInstance().getShader(GraphicsManager.ShaderPrograms.TILE_RENDERER);
        shaderProgram.use();

        // Bind the palette texture (actual colours)
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, paletteTextureId);

        // Bind the pattern texture (colour indexes)
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, patternTextureId);

        // Set sampler uniforms to the correct texture units
        int paletteLocation = GL20.glGetUniformLocation(shaderProgram.getProgramId(), "Palette");
        GL20.glUniform1i(paletteLocation, 0); // Texture unit 0

        int indexedColorTextureLocation = GL20.glGetUniformLocation(shaderProgram.getProgramId(), "IndexedColorTexture");
        GL20.glUniform1i(indexedColorTextureLocation, 1); // Texture unit 1

        // Define the vertices for a quad (2 triangles)
        float[] vertices = {
                0.0f, 0.0f,  // Bottom-left
                8.0f, 0.0f,  // Bottom-right
                8.0f, 8.0f,  // Top-right
                0.0f, 8.0f   // Top-left
        };

        // Define the texture coordinates for the quad
        float[] texCoords = {
                0.0f, 0.0f,  // Bottom-left
                1.0f, 0.0f,  // Bottom-right
                1.0f, 1.0f,  // Top-right
                0.0f, 1.0f   // Top-left
        };

        // Create and upload the vertex data to a buffer
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        FloatBuffer texCoordBuffer = BufferUtils.createFloatBuffer(texCoords.length);
        texCoordBuffer.put(texCoords).flip();

        // Enable vertex array and texture coordinate array
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

        // Bind vertex data
        GL11.glVertexPointer(2, GL11.GL_FLOAT, 0, vertexBuffer);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 0, texCoordBuffer);

        // Draw the quad as two triangles forming a rectangle
        GL11.glDrawArrays(GL11.GL_QUADS, 0, 4);

        // Disable vertex and texture coordinate arrays
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

        // Stop the shader program
        shaderProgram.stop();

        GL11.glPopMatrix();
        GL11.glDisable(GL11.GL_BLEND);
    }
}
