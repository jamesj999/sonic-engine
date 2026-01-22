#version 110

attribute vec2 VertexPos;
attribute vec2 InstancePos;
attribute vec2 InstanceSize;
attribute vec2 InstanceUv0;
attribute vec2 InstanceUv1;
attribute float InstancePalette;

void main()
{
    vec2 pos = InstancePos + (VertexPos * InstanceSize);
    gl_Position = gl_ModelViewProjectionMatrix * vec4(pos, 0.0, 1.0);

    vec2 uv = mix(InstanceUv0, InstanceUv1, VertexPos);
    gl_TexCoord[0] = vec4(uv, 0.0, 1.0);
    gl_TexCoord[1] = vec4(InstancePalette, 0.0, 0.0, 1.0);
}
