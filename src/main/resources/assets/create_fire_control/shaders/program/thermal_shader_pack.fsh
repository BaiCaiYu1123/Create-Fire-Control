#version 150

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
out vec4 fragColor;

float luminance(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    // Safety fallback only. The active shader-pack thermal path now bypasses
    // Iris lighting and tonemapping before this stage, so the response must be
    // strictly monotonic and must never apply inverse local exposure.
    vec3 scene = texture(DiffuseSampler, texCoord).rgb;
    float gray = luminance(scene);
    float whiteHot = 0.018 + pow(clamp(gray, 0.0, 1.0), 1.05) * 0.82;
    fragColor = vec4(vec3(clamp(whiteHot, 0.0, 1.0)), 1.0);
}
