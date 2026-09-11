#version 410 core

layout(location = 0) in vec2 VertexPos;
layout(location = 1) in vec2 VertexUv;
layout(location = 2) in vec4 VertexColor;

uniform mat4 ProjectionMatrix;

out vec2 v_texCoord;
out vec4 v_color;

void main()
{
    gl_Position = ProjectionMatrix * vec4(VertexPos, 0.0, 1.0);
    v_texCoord = VertexUv;
    v_color = VertexColor;
}
