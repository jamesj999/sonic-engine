package uk.co.jamesj999.sonic.graphics;

import com.jogamp.opengl.GL2;
import java.io.IOException;

/**
 * Shader program specialized for parallax background rendering.
 * Extends the base shader functionality with uniforms for per-scanline
 * scrolling.
 */
public class ParallaxShaderProgram {

    private int programId;
    private boolean uniformsCached = false;

    // Texture sampler locations
    private int backgroundTextureLocation = -1;
    private int hScrollTextureLocation = -1;
    private int paletteLocation = -1;

    // Scroll and dimension uniforms
    private int screenHeightLocation = -1;
    private int screenWidthLocation = -1;
    private int vScrollBGLocation = -1;
    private int bgTextureWidthLocation = -1;
    private int bgTextureHeightLocation = -1;
    private int scrollMidpointLocation = -1;
    private int extraBufferLocation = -1;
    private int vScrollLocation = -1;

    /**
     * Creates and links the parallax shader program.
     * 
     * @param gl                 OpenGL context
     * @param fragmentShaderPath Path to the fragment shader file
     * @throws IOException if shader loading fails
     */
    public ParallaxShaderProgram(GL2 gl, String fragmentShaderPath) throws IOException {
        int fragmentShaderId = ShaderLoader.loadShader(gl, fragmentShaderPath, GL2.GL_FRAGMENT_SHADER);

        programId = gl.glCreateProgram();
        gl.glAttachShader(programId, fragmentShaderId);
        gl.glLinkProgram(programId);

        // Check for linking errors
        int[] linked = new int[1];
        gl.glGetProgramiv(programId, GL2.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            int[] logLength = new int[1];
            gl.glGetProgramiv(programId, GL2.GL_INFO_LOG_LENGTH, logLength, 0);
            byte[] log = new byte[logLength[0]];
            gl.glGetProgramInfoLog(programId, log.length, null, 0, log, 0);
            System.err.println("Parallax shader linking failed:\n" + new String(log));
        }
    }

    /**
     * Cache all uniform locations for efficient access.
     */
    public void cacheUniformLocations(GL2 gl) {
        if (uniformsCached) {
            return;
        }

        // Texture samplers
        backgroundTextureLocation = gl.glGetUniformLocation(programId, "BackgroundTexture");
        hScrollTextureLocation = gl.glGetUniformLocation(programId, "HScrollTexture");
        paletteLocation = gl.glGetUniformLocation(programId, "Palette");

        // Scroll and dimensions
        screenHeightLocation = gl.glGetUniformLocation(programId, "ScreenHeight");
        screenWidthLocation = gl.glGetUniformLocation(programId, "ScreenWidth");
        vScrollBGLocation = gl.glGetUniformLocation(programId, "VScrollBG");
        bgTextureWidthLocation = gl.glGetUniformLocation(programId, "BGTextureWidth");
        bgTextureHeightLocation = gl.glGetUniformLocation(programId, "BGTextureHeight");
        scrollMidpointLocation = gl.glGetUniformLocation(programId, "ScrollMidpoint");
        extraBufferLocation = gl.glGetUniformLocation(programId, "ExtraBuffer");
        vScrollLocation = gl.glGetUniformLocation(programId, "VScroll");

        uniformsCached = true;
    }

    public void use(GL2 gl) {
        gl.glUseProgram(programId);
    }

    public void stop(GL2 gl) {
        gl.glUseProgram(0);
    }

    public int getProgramId() {
        return programId;
    }

    // Texture unit setters
    public void setBackgroundTexture(GL2 gl, int textureUnit) {
        if (backgroundTextureLocation >= 0) {
            gl.glUniform1i(backgroundTextureLocation, textureUnit);
        }
    }

    public void setHScrollTexture(GL2 gl, int textureUnit) {
        if (hScrollTextureLocation >= 0) {
            gl.glUniform1i(hScrollTextureLocation, textureUnit);
        }
    }

    public void setPalette(GL2 gl, int textureUnit) {
        if (paletteLocation >= 0) {
            gl.glUniform1i(paletteLocation, textureUnit);
        }
    }

    // Dimension and scroll setters
    public void setScreenDimensions(GL2 gl, float width, float height) {
        if (screenWidthLocation >= 0) {
            gl.glUniform1f(screenWidthLocation, width);
        }
        if (screenHeightLocation >= 0) {
            gl.glUniform1f(screenHeightLocation, height);
        }
    }

    public void setVScrollBG(GL2 gl, float vScroll) {
        if (vScrollBGLocation >= 0) {
            gl.glUniform1f(vScrollBGLocation, vScroll);
        }
    }

    public void setBGTextureDimensions(GL2 gl, float width, float height) {
        if (bgTextureWidthLocation >= 0) {
            gl.glUniform1f(bgTextureWidthLocation, width);
        }
        if (bgTextureHeightLocation >= 0) {
            gl.glUniform1f(bgTextureHeightLocation, height);
        }
    }

    public void setScrollMidpoint(GL2 gl, int midpoint) {
        if (scrollMidpointLocation >= 0) {
            gl.glUniform1f(scrollMidpointLocation, (float) midpoint);
        }
    }

    public void setExtraBuffer(GL2 gl, int buffer) {
        if (extraBufferLocation >= 0) {
            gl.glUniform1f(extraBufferLocation, (float) buffer);
        }
    }

    public void setVScroll(GL2 gl, float vScroll) {
        if (vScrollLocation >= 0) {
            gl.glUniform1f(vScrollLocation, vScroll);
        }
    }

    public void cleanup(GL2 gl) {
        if (programId != 0) {
            gl.glDeleteProgram(programId);
            programId = 0;
        }
    }
}
