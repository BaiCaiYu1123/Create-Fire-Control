#version 150
in vec3 Position;
in vec4 Color;
in vec2 UV0;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform float DepthPass;
out vec4 vertexColor;
out vec2 depthAnchor;
out float elementDepth;
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position.xy, 0.0, 1.0);
    if (DepthPass > 0.5) gl_Position.z = Position.z * gl_Position.w;
    vertexColor = Color;
    depthAnchor = UV0;
    elementDepth = Position.z * 0.5 + 0.5;
}
