#version 150
uniform sampler2D SceneDepth;
uniform vec4 ColorModulator;
uniform vec2 ViewportSize;
uniform float Xray;
uniform float DepthBias;
uniform float DepthPass;
in vec4 vertexColor;
in vec2 depthAnchor;
in float elementDepth;
out vec4 fragColor;
void main() {
    if (DepthPass > 0.5) { fragColor = vec4(0.0); return; }
    vec2 uv = depthAnchor.x >= 0.0 ? depthAnchor : gl_FragCoord.xy / ViewportSize;
    bool hidden = elementDepth > texture(SceneDepth, uv).r + DepthBias;
    if (hidden && Xray < 0.5) discard;
    vec4 color = vertexColor * ColorModulator;
    if (hidden) color.a *= 0.28;
    if (color.a < 0.001) discard;
    fragColor = color;
}
