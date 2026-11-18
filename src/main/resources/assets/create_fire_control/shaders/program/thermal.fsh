#version 150

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
out vec4 fragColor;

float luminance(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    // Lighting has already been replaced by the fixed thermal light map.
    // This pass only converts the completed, correctly depth-sorted scene to
    // white-hot monochrome; it never samples neighbouring pixels or geometry.
    vec3 scene = texture(DiffuseSampler, texCoord).rgb;
    float gray = luminance(scene);

    // Fixed response curve. Source-emitting blocks and living entities have
    // already received an RGB-only white-hot floor; propagated block light
    // never enters that path. Sable sub-levels arrive with their own floor.
    float whiteHot = pow(clamp(gray, 0.0, 1.0), 1.05);
    whiteHot = 0.018 + whiteHot * 0.82;

    fragColor = vec4(vec3(clamp(whiteHot, 0.0, 1.0)), 1.0);
}
