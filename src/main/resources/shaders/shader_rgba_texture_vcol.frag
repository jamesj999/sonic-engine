#version 410 core

uniform sampler2D Texture;

in vec2 v_texCoord;
in vec4 v_color;
out vec4 FragColor;

void main()
{
    FragColor = texture(Texture, v_texCoord) * v_color;
    if (FragColor.a < 0.01) discard;
}
