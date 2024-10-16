package uk.co.jamesj999.sonic.graphics;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.GLBuffers;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.level.PatternDesc;

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
    public void execute(GL2 gl, int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
        gl.glPushMatrix();
        gl.glEnable(GL2.GL_BLEND);
        gl.glBlendFunc(GL2.GL_SRC_ALPHA,GL2.GL_ONE_MINUS_SRC_ALPHA);

        // Translate the pattern relative to the camera position
        gl.glTranslatef(x - cameraX, y + cameraY, 0);

        // Apply horizontal and vertical flips
        if (desc.getHFlip()) {
            gl.glScalef(-1, 1, 1);  // Horizontal flip
            gl.glTranslatef(-8, 0, 0);  // Adjust for flipping
        }
        if (desc.getVFlip()) {
            gl.glScalef(1, -1, 1);  // Vertical flip
            gl.glTranslatef(0, -8, 0);  // Adjust for flipping
        }

        // Bind the shader program
        ShaderProgram shaderProgram = GraphicsManager.getInstance().getShaderProgram();
        shaderProgram.use(gl);

        // Bind the palette texture (actual colours)
        gl.glActiveTexture(GL2.GL_TEXTURE0);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, paletteTextureId);

        // Bind the pattern texture (colour indexes)
        gl.glActiveTexture(GL2.GL_TEXTURE1);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, patternTextureId);

        // Set sampler uniforms to the correct texture units
        int paletteLocation = gl.glGetUniformLocation(shaderProgram.getProgramId(), "Palette");
        gl.glUniform1i(paletteLocation, 0); // Texture unit 0

        int indexedColorTextureLocation = gl.glGetUniformLocation(shaderProgram.getProgramId(), "IndexedColorTexture");
        gl.glUniform1i(indexedColorTextureLocation, 1); // Texture unit 1

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

        // Bind the vertex data
        gl.glEnableClientState(GL2.GL_VERTEX_ARRAY);
        gl.glVertexPointer(2, GL2.GL_FLOAT, 0, GLBuffers.newDirectFloatBuffer(vertices));

        // Bind the texture coordinate data
        gl.glEnableClientState(GL2.GL_TEXTURE_COORD_ARRAY);
        gl.glTexCoordPointer(2, GL2.GL_FLOAT, 0, GLBuffers.newDirectFloatBuffer(texCoords));

        // Draw the quad as two triangles forming a rectangle
        gl.glDrawArrays(GL2.GL_QUADS, 0, 4);

        // Disable the client states
        gl.glDisableClientState(GL2.GL_VERTEX_ARRAY);
        gl.glDisableClientState(GL2.GL_TEXTURE_COORD_ARRAY);

        // Stop the shader program
        shaderProgram.stop(gl);

        gl.glPopMatrix();
        gl.glDisable(GL2.GL_BLEND);
    }
}
