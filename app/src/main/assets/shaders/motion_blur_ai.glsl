precision highp float;
varying vec2 vTextureCoord;
uniform sampler2D sTexture;
uniform sampler2D uAiFlowTexture;
uniform vec2 uTexelSize;
uniform float uBlur;
uniform float uBlend;
uniform float uDirection;
uniform float uTimes;
uniform float uFlowReady;
uniform float uMaxFlowPixels;
uniform float uDiagnostic;

vec3 flowHue(float h) {
    vec3 rgb = abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0;
    return clamp(rgb, 0.0, 1.0);
}
float segmentDistance(vec2 p, vec2 a, vec2 b) {
    vec2 ab = b - a;
    float t = clamp(dot(p - a, ab) / max(dot(ab, ab), 0.0001), 0.0, 1.0);
    return length(p - (a + t * ab));
}
void main() {
    vec4 current = texture2D(sTexture, vTextureCoord);
    if (uFlowReady < 0.5 || uBlur <= 0.001 || uBlend <= 0.001) {
        gl_FragColor = current;
        return;
    }

    vec2 encoded = texture2D(uAiFlowTexture, vTextureCoord).rg;
    vec2 flowPixels = (encoded - vec2(0.5)) * 2.0 * uMaxFlowPixels;
    float speed = length(flowPixels);
    if (uDiagnostic > 0.5) {
        float angle = atan(flowPixels.y, flowPixels.x);
        float hue = fract(angle / 6.2831853 + 1.0);
        float magnitude = clamp(speed / 18.0, 0.0, 1.0);
        vec3 flowColor = flowHue(hue) * (0.18 + 0.82 * magnitude);
        vec3 diagnosticColor = mix(current.rgb * 0.28, flowColor, 0.88);
        vec2 pixel = vTextureCoord / uTexelSize;
        vec2 cell = floor(pixel / 32.0) * 32.0 + 16.0;
        vec2 anchorUv = clamp(cell * uTexelSize, uTexelSize * 0.5, vec2(1.0) - uTexelSize * 0.5);
        vec2 anchorFlow = (texture2D(uAiFlowTexture, anchorUv).rg - vec2(0.5)) * 2.0 * uMaxFlowPixels;
        float anchorSpeed = length(anchorFlow);
        vec2 endpoint = cell + anchorFlow * 2.0;
        float shaft = segmentDistance(pixel, cell, endpoint);
        vec2 arrowDir = normalize(anchorFlow + vec2(0.0001));
        float headA = length(pixel - (endpoint - arrowDir * 5.0 + vec2(-arrowDir.y, arrowDir.x) * 4.0));
        float headB = length(pixel - (endpoint - arrowDir * 5.0 - vec2(-arrowDir.y, arrowDir.x) * 4.0));
        float arrowMask = max(1.0 - smoothstep(1.0, 2.2, shaft),
                              1.0 - smoothstep(1.0, 2.2, min(headA, headB)));
        if (anchorSpeed < 0.8) arrowMask = 0.0;
        diagnosticColor = mix(diagnosticColor, vec3(1.0), arrowMask);
        gl_FragColor = vec4(diagnosticColor, 1.0);
        return;
    }
    if (speed < 0.45) {
        gl_FragColor = current;
        return;
    }

    vec2 direction = flowPixels / max(speed, 0.0001);
    float radius = min(speed * (0.8 + 3.8 * uBlur) * clamp(uTimes, 1.0, 6.0) * 1.35, 150.0);
    vec2 streak = direction * radius * uTexelSize;

    vec3 accumulated = vec3(0.0);
    float weightSum = 0.0;
    const int TAPS = 40;
    for (int i = 0; i < TAPS; i++) {
        float t = (float(i) + 0.5) / float(TAPS);
        float position;
        float weight;
        if (uDirection < 0.5) {
            position = t * 2.0 - 1.0;
            weight = exp(-1.8 * position * position);
        } else if (uDirection < 1.5) {
            position = -t;
            weight = exp(-1.25 * t);
        } else {
            position = t;
            weight = exp(-1.25 * t);
        }
        vec2 sampleUv = clamp(vTextureCoord + streak * position,
                              uTexelSize * 0.5, vec2(1.0) - uTexelSize * 0.5);
        accumulated += texture2D(sTexture, sampleUv).rgb * weight;
        weightSum += weight;
    }
    vec3 smear = accumulated / max(weightSum, 0.0001);
    float fastMotion = smoothstep(1.0, 10.0, speed);
    float detailSuppression = clamp(uBlur * (0.35 + fastMotion * 0.65), 0.0, 1.0);
    float strength = clamp(uBlend * detailSuppression, 0.0, 1.0);
    gl_FragColor = vec4(mix(current.rgb, smear, strength), current.a);
}
