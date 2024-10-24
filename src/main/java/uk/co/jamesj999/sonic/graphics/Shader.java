package uk.co.jamesj999.sonic.graphics;

import org.lwjgl.opengl.GL20;

public class Shader {
    private final Type type;
    private final String path;

    public Type getType() {
        return type;
    }

    public String getPath() {
        return path;
    }

    public enum Type {
        PLAIN(GL20.GL_VERTEX_SHADER),
        FRAGMENT(GL20.GL_FRAGMENT_SHADER),
        VERTEX(GL20.GL_VERTEX_SHADER);

        final int gl20;
        Type(int glVertexShader) {
            this.gl20 = glVertexShader;
        }
    }

    public Shader(Type type, String path) {
        this.type = type;
        this.path = path;

    }
}
