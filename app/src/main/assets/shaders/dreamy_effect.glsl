precision highp float;

varying vec2 vTextureCoord;
uniform sampler2D sTexture;
uniform float uIntensity;
uniform float uHasColorGrade;
uniform float uBlurRadius;
uniform float uWarmth;
uniform float uEffectMode;
uniform float uSoftFocus;
uniform float uPullInZoom;
uniform float uPullInBlur;
uniform float uPullInFade;
uniform float uExposure;
uniform float uContrast;
uniform float uHighlights;
uniform float uShadows;
uniform float uWhites;
uniform float uBlacks;
uniform float uTemperature;
uniform float uTint;
uniform float uVibrance;
uniform float uSaturation;
uniform float uTexture;
uniform float uClarity;
uniform float uDehaze;
uniform float uVignette;
uniform float uGrain;
uniform float uSharpening;
uniform float uNoiseReduction;
uniform float uRedHue;
uniform float uRedSaturation;
uniform float uRedLuminance;
uniform float uOrangeHue;
uniform float uOrangeSaturation;
uniform float uOrangeLuminance;
uniform float uYellowHue;
uniform float uYellowSaturation;
uniform float uYellowLuminance;
uniform float uGreenHue;
uniform float uGreenSaturation;
uniform float uGreenLuminance;
uniform float uAquaHue;
uniform float uAquaSaturation;
uniform float uAquaLuminance;
uniform float uBlueHue;
uniform float uBlueSaturation;
uniform float uBlueLuminance;
uniform float uPurpleHue;
uniform float uPurpleSaturation;
uniform float uPurpleLuminance;
uniform float uMagentaHue;
uniform float uMagentaSaturation;
uniform float uMagentaLuminance;

vec3 screenBlend(vec3 baseColor, vec3 glowColor) {
    return 1.0 - ((1.0 - baseColor) * (1.0 - glowColor));
}

float luminanceOf(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}


vec3 rgbToHsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
vec3 hsvToRgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}
void correctHueRange(inout vec3 hsv, float center, float hueShift, float satShift, float lumShift) {
    float d = abs(fract(hsv.x - center + 0.5) - 0.5);
    float mask = 1.0 - smoothstep(0.045, 0.115, d);
    hsv.x = fract(hsv.x + hueShift * 0.005 * mask);
    hsv.y = clamp(hsv.y + satShift * 0.01 * mask, 0.0, 1.0);
    hsv.z = clamp(hsv.z + lumShift * 0.003 * mask, 0.0, 1.0);
}
vec3 applyColorCorrection(vec3 color) {
    vec3 hsv = rgbToHsv(color);
    correctHueRange(hsv, 0.0000, uRedHue, uRedSaturation, uRedLuminance);
    correctHueRange(hsv, 0.0833, uOrangeHue, uOrangeSaturation, uOrangeLuminance);
    correctHueRange(hsv, 0.1667, uYellowHue, uYellowSaturation, uYellowLuminance);
    correctHueRange(hsv, 0.3333, uGreenHue, uGreenSaturation, uGreenLuminance);
    correctHueRange(hsv, 0.5000, uAquaHue, uAquaSaturation, uAquaLuminance);
    correctHueRange(hsv, 0.6667, uBlueHue, uBlueSaturation, uBlueLuminance);
    correctHueRange(hsv, 0.7778, uPurpleHue, uPurpleSaturation, uPurpleLuminance);
    correctHueRange(hsv, 0.9167, uMagentaHue, uMagentaSaturation, uMagentaLuminance);
    return hsvToRgb(hsv);
}

vec3 applyColorGrading(vec3 color) {
    vec3 c = color * exp2(uExposure);
    c = (c - vec3(0.5)) * (1.0 + uContrast * 0.01) + vec3(0.5);

    float luma = luminanceOf(c);
    float shadowMask = 1.0 - smoothstep(0.02, 0.62, luma);
    float highlightMask = smoothstep(0.38, 0.98, luma);
    float whiteMask = smoothstep(0.68, 1.0, luma);
    float blackMask = 1.0 - smoothstep(0.0, 0.30, luma);
    c += vec3(uShadows * 0.0032 * shadowMask);
    c += vec3(uHighlights * 0.0032 * highlightMask);
    c += vec3(uWhites * 0.0022 * whiteMask);
    c += vec3(uBlacks * 0.0022 * blackMask);

    c.r *= 1.0 + uTemperature * 0.0015;
    c.b *= 1.0 - uTemperature * 0.0015;
    c.r += uTint * 0.00045;
    c.b -= uTint * 0.00045;

    float gray = luminanceOf(c);
    float chroma = max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b);
    float vibranceBoost = uVibrance * 0.01 * (1.0 - clamp(chroma, 0.0, 1.0));
    c = mix(vec3(gray), c, max(0.0, 1.0 + uSaturation * 0.01 + vibranceBoost));
    c = (c - vec3(0.5)) * (1.0 + uDehaze * 0.005) + vec3(0.5);
    return clamp(c, 0.0, 1.0);
}

void main() {
    vec2 sampleCoord = clamp((vTextureCoord - vec2(0.5)) / max(uPullInZoom, 1.0) + vec2(0.5), 0.0, 1.0);
    vec4 sharpSample = texture2D(sTexture, sampleCoord);
    vec2 pullStep = vec2(0.003 + uPullInBlur * 0.012);
    vec3 pullBlurred = sharpSample.rgb * 0.20;
    pullBlurred += texture2D(sTexture, clamp(sampleCoord + vec2(pullStep.x, 0.0), 0.0, 1.0)).rgb * 0.12;
    pullBlurred += texture2D(sTexture, clamp(sampleCoord - vec2(pullStep.x, 0.0), 0.0, 1.0)).rgb * 0.12;
    pullBlurred += texture2D(sTexture, clamp(sampleCoord + vec2(0.0, pullStep.y), 0.0, 1.0)).rgb * 0.12;
    pullBlurred += texture2D(sTexture, clamp(sampleCoord - vec2(0.0, pullStep.y), 0.0, 1.0)).rgb * 0.12;
    pullBlurred += texture2D(sTexture, clamp(sampleCoord + pullStep, 0.0, 1.0)).rgb * 0.08;
    pullBlurred += texture2D(sTexture, clamp(sampleCoord - pullStep, 0.0, 1.0)).rgb * 0.08;
    pullBlurred += texture2D(sTexture, clamp(sampleCoord + vec2(pullStep.x, -pullStep.y), 0.0, 1.0)).rgb * 0.08;
    pullBlurred += texture2D(sTexture, clamp(sampleCoord + vec2(-pullStep.x, pullStep.y), 0.0, 1.0)).rgb * 0.08;
    vec4 sampled = vec4(mix(sharpSample.rgb, pullBlurred, clamp(uPullInBlur, 0.0, 1.0)), sharpSample.a);
    // Fade transisi diarahkan ke hitam agar preview dan hasil export konsisten.
    sampled.rgb *= 1.0 - clamp(uPullInFade, 0.0, 1.0);

    // Jalur normal: lewati HSV, kernel detail, blur 8 arah, dan kalkulasi efek.
    if (uIntensity <= 0.001 && uHasColorGrade < 0.5 && uPullInBlur <= 0.001 && uPullInFade <= 0.001 && abs(uPullInZoom - 1.0) <= 0.001) {
        gl_FragColor = sampled;
        return;
    }
    vec3 graded = applyColorCorrection(applyColorGrading(sampled.rgb));

    // Local contrast/detail controls use a compact neighborhood kernel.
    vec2 detailStep = vec2(0.0015);
    vec3 neighbor = (
        texture2D(sTexture, clamp(sampleCoord + vec2(detailStep.x, 0.0), 0.0, 1.0)).rgb +
        texture2D(sTexture, clamp(sampleCoord - vec2(detailStep.x, 0.0), 0.0, 1.0)).rgb +
        texture2D(sTexture, clamp(sampleCoord + vec2(0.0, detailStep.y), 0.0, 1.0)).rgb +
        texture2D(sTexture, clamp(sampleCoord - vec2(0.0, detailStep.y), 0.0, 1.0)).rgb
    ) * 0.25;
    vec3 detail = sampled.rgb - neighbor;
    graded += detail * (uTexture * 0.012 + uClarity * 0.018 + uSharpening * 0.022);
    graded = mix(graded, neighbor, clamp(uNoiseReduction * 0.01, 0.0, 0.85));

    vec2 gradeCenter = sampleCoord - vec2(0.5);
    float vignetteMask = clamp(dot(gradeCenter, gradeCenter) * 2.0, 0.0, 1.0);
    graded *= 1.0 - uVignette * 0.006 * vignetteMask;

    float noise = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
    graded += noise * uGrain * 0.0015;
    vec4 original = vec4(clamp(graded, 0.0, 1.0), sampled.a);

    // Mode 1: Black & White — dipertahankan seperti versi stabil.
    if (uEffectMode > 0.5 && uEffectMode < 1.5) {
        float luminance = dot(original.rgb, vec3(0.2126, 0.7152, 0.0722));
        float monochrome = clamp((luminance - 0.5) * 1.12 + 0.5, 0.0, 1.0);
        gl_FragColor = vec4(mix(original.rgb, vec3(monochrome), clamp(uIntensity, 0.0, 1.0)), original.a);
        return;
    }

    vec2 r = vec2(max(uBlurRadius, 0.0005));
    vec3 blurred = original.rgb * 0.25;
    blurred += texture2D(sTexture, clamp(sampleCoord + vec2( r.x, 0.0), 0.0, 1.0)).rgb * 0.125;
    blurred += texture2D(sTexture, clamp(sampleCoord + vec2(-r.x, 0.0), 0.0, 1.0)).rgb * 0.125;
    blurred += texture2D(sTexture, clamp(sampleCoord + vec2(0.0,  r.y), 0.0, 1.0)).rgb * 0.125;
    blurred += texture2D(sTexture, clamp(sampleCoord + vec2(0.0, -r.y), 0.0, 1.0)).rgb * 0.125;
    blurred += texture2D(sTexture, clamp(sampleCoord + r, 0.0, 1.0)).rgb * 0.0625;
    blurred += texture2D(sTexture, clamp(sampleCoord - r, 0.0, 1.0)).rgb * 0.0625;
    blurred += texture2D(sTexture, clamp(sampleCoord + vec2(r.x, -r.y), 0.0, 1.0)).rgb * 0.0625;
    blurred += texture2D(sTexture, clamp(sampleCoord + vec2(-r.x, r.y), 0.0, 1.0)).rgb * 0.0625;

    float strength = clamp(uIntensity, 0.0, 1.0);
    float focus = clamp(uSoftFocus, 0.0, 1.0);

    // Mode 2: Cahaya Lembut. Diffusion veil yang menyatukan cahaya
    // dengan detail asli, bukan sekadar memberi tint warna.
    if (uEffectMode > 1.5) {
        vec3 softBase = mix(original.rgb, blurred, focus * 0.72);
        vec3 brightSource = max(blurred - vec3(0.28), vec3(0.0));
        brightSource = brightSource * brightSource * 2.0;
        vec3 halo = screenBlend(softBase, brightSource * (0.45 + strength * 1.8));

        // Lapisan cahaya putih lembut, dengan sedikit kendali suhu warna.
        vec3 lightTint = mix(vec3(1.0, 0.91, 0.78), vec3(0.86, 0.93, 1.0), 1.0 - uWarmth);
        vec3 veil = mix(blurred, lightTint, 0.28 + focus * 0.18);
        vec3 result = mix(softBase, halo, 0.25 + strength * 0.48);
        result = screenBlend(result, veil * (0.10 + strength * 0.24));
        result = mix(result, vec3(1.0), strength * focus * 0.055);
        gl_FragColor = vec4(clamp(mix(original.rgb, result, strength), 0.0, 1.0), original.a);
        return;
    }

    // Mode 0: Dreamy berbasis pengurangan detail.
    // Blur dihitung dari sampel frame yang sama; tidak ada frame/objek tambahan.
    // Radius diperbesar agar perubahan detail benar-benar terlihat di preview dan export.
    vec2 dreamyStep = r * (2.2 + focus * 2.8);
    vec3 dreamyBlur = original.rgb * 0.24;
    dreamyBlur += texture2D(sTexture, clamp(sampleCoord + vec2(dreamyStep.x, 0.0), 0.0, 1.0)).rgb * 0.12;
    dreamyBlur += texture2D(sTexture, clamp(sampleCoord - vec2(dreamyStep.x, 0.0), 0.0, 1.0)).rgb * 0.12;
    dreamyBlur += texture2D(sTexture, clamp(sampleCoord + vec2(0.0, dreamyStep.y), 0.0, 1.0)).rgb * 0.12;
    dreamyBlur += texture2D(sTexture, clamp(sampleCoord - vec2(0.0, dreamyStep.y), 0.0, 1.0)).rgb * 0.12;
    dreamyBlur += texture2D(sTexture, clamp(sampleCoord + dreamyStep, 0.0, 1.0)).rgb * 0.07;
    dreamyBlur += texture2D(sTexture, clamp(sampleCoord - dreamyStep, 0.0, 1.0)).rgb * 0.07;
    dreamyBlur += texture2D(sTexture, clamp(sampleCoord + vec2(dreamyStep.x, -dreamyStep.y), 0.0, 1.0)).rgb * 0.07;
    dreamyBlur += texture2D(sTexture, clamp(sampleCoord + vec2(-dreamyStep.x, dreamyStep.y), 0.0, 1.0)).rgb * 0.07;

    // Intensity mengatur seberapa kuat detail asli dilembutkan.
    // Soft Focus menambah tingkat pengurangan detail secara terpisah.
    float detailReduction = clamp(strength * (0.28 + focus * 0.62), 0.0, 0.88);
    vec3 dreamy = mix(original.rgb, dreamyBlur, detailReduction);

    // Sedikit angkat midtone dari frame yang sama untuk karakter dreamy,
    // tanpa menambahkan sumber cahaya atau warna yang tidak ada di clip.
    float midtone = smoothstep(0.12, 0.82, luminanceOf(dreamy));
    dreamy = mix(dreamy, min(dreamy + vec3(0.035), vec3(1.0)), midtone * strength * 0.12);
    dreamy.r += uWarmth * 0.012 * strength;
    dreamy.b += (1.0 - uWarmth) * 0.008 * strength;

    gl_FragColor = vec4(clamp(dreamy, 0.0, 1.0), original.a);
}