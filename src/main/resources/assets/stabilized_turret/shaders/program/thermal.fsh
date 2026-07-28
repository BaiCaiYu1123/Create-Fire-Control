#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;

in vec2 texCoord;
out vec4 fragColor;

float luminance(vec3 color) {
    return dot(color, vec3(0.299, 0.587, 0.114));
}

float heatScore(vec3 color) {
    float center = luminance(color);
    float hottest = max(max(color.r, color.g), color.b);
    float coldest = min(min(color.r, color.g), color.b);
    float saturation = hottest - coldest;

    // Neutral white-hot targets. Entities receive an explicit white thermal
    // overlay and Sable structures receive an explicit render multiplier, so
    // this is a real target mask rather than merely raising scene exposure.
    float neutral = smoothstep(0.72, 0.94, center)
        * (1.0 - smoothstep(0.20, 0.52, saturation));

    // Powered redstone, lava and ordinary fire.
    float redHeat = smoothstep(0.10, 0.42, color.r - max(color.g, color.b) * 0.54)
        * smoothstep(0.16, 0.54, color.r);

    // Soul fire, soul torches and soul campfires.
    float soulHeat = smoothstep(0.07, 0.36, max(color.g, color.b) - color.r * 0.64)
        * smoothstep(0.18, 0.60, max(color.g, color.b));
    return max(neutral, max(redHeat, soulHeat));
}

void main() {
    vec2 px = 1.0 / max(InSize, vec2(1.0));
    vec3 color = texture(DiffuseSampler, texCoord).rgb;
    float center = luminance(color);

    float nearAverage = (
        luminance(texture(DiffuseSampler, texCoord + vec2(px.x, 0.0)).rgb) +
        luminance(texture(DiffuseSampler, texCoord - vec2(px.x, 0.0)).rgb) +
        luminance(texture(DiffuseSampler, texCoord + vec2(0.0, px.y)).rgb) +
        luminance(texture(DiffuseSampler, texCoord - vec2(0.0, px.y)).rgb)
    ) * 0.25;
    // Work in log luminance so a uniform change from daylight to moonlight is
    // removed before the thermal palette is applied. The epsilon is low
    // enough to retain texture relief in a dark scene, but high enough to
    // avoid amplifying black-level noise.
    float fineLogDetail = log2(0.008 + center) - log2(0.008 + nearAverage);
    float signedDetail = clamp(fineLogDetail * 0.18, -0.16, 0.22);
    float edge = clamp(abs(fineLogDetail) * 0.30, 0.0, 0.28);

    // A compact multi-scale neighbourhood recovers terrain and vehicle form
    // that spans more than one pixel. The old 10/34-pixel taps produced
    // displaced, zoom-dependent ghosts; these 2/5-pixel taps stay local and
    // are used only to estimate exposure, never copied into the output.
    vec2 r1 = px * 2.0;
    vec2 r2 = px * 5.0;
    float localLog = (
        log2(0.008 + luminance(texture(DiffuseSampler, texCoord + vec2(r1.x, 0.0)).rgb)) +
        log2(0.008 + luminance(texture(DiffuseSampler, texCoord - vec2(r1.x, 0.0)).rgb)) +
        log2(0.008 + luminance(texture(DiffuseSampler, texCoord + vec2(0.0, r1.y)).rgb)) +
        log2(0.008 + luminance(texture(DiffuseSampler, texCoord - vec2(0.0, r1.y)).rgb)) +
        log2(0.008 + luminance(texture(DiffuseSampler, texCoord + vec2(r2.x, r2.y)).rgb)) +
        log2(0.008 + luminance(texture(DiffuseSampler, texCoord + vec2(-r2.x, r2.y)).rgb)) +
        log2(0.008 + luminance(texture(DiffuseSampler, texCoord + vec2(r2.x, -r2.y)).rgb)) +
        log2(0.008 + luminance(texture(DiffuseSampler, texCoord - vec2(r2.x, r2.y)).rgb))
    ) * 0.125;
    float illuminationFreeDetail = clamp(
        (log2(0.008 + center) - localLog) * 0.145,
        -0.16,
        0.19
    );

    float heat = heatScore(color);
    float nearbyHeat = 0.0;
    nearbyHeat = max(nearbyHeat, heatScore(texture(DiffuseSampler, texCoord + vec2(px.x * 3.0, 0.0)).rgb));
    nearbyHeat = max(nearbyHeat, heatScore(texture(DiffuseSampler, texCoord - vec2(px.x * 3.0, 0.0)).rgb));
    nearbyHeat = max(nearbyHeat, heatScore(texture(DiffuseSampler, texCoord + vec2(0.0, px.y * 3.0)).rgb));
    nearbyHeat = max(nearbyHeat, heatScore(texture(DiffuseSampler, texCoord - vec2(0.0, px.y * 3.0)).rgb));

    // Fixed-exposure WHOT palette. Cold terrain no longer contains absolute
    // visible-spectrum luminance, so daytime, moonlight and nearby lamps do
    // not alter its thermal brightness. Hot masks retain restrained texture
    // relief and never collapse into a featureless white silhouette.
    float cold = 0.225 + illuminationFreeDetail + signedDetail * 0.16;
    float whiteHot = 0.765 + illuminationFreeDetail * 0.58 + signedDetail * 0.30;
    float thermal = mix(cold, whiteHot, heat);
    thermal += edge * (0.055 + heat * 0.14);
    thermal += max(nearbyHeat - heat, 0.0) * 0.075;

    // Subtle sensor character: fixed scan lines and restrained grain. Neither
    // changes the screen-centre ray or introduces temporal flicker.
    float scanLine = sin(gl_FragCoord.y * 1.5707963) * 0.006;
    float grain = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
    vec2 lens = texCoord * 2.0 - 1.0;
    float edgeFalloff = 1.0 - smoothstep(0.58, 1.18, length(lens * vec2(0.78, 1.0)));
    thermal = thermal * mix(0.86, 1.0, edgeFalloff) + scanLine + grain * 0.008;

    fragColor = vec4(vec3(clamp(thermal, 0.012, 0.985)), 1.0);
}
