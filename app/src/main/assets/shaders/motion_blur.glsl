precision highp float;
varying vec2 vTextureCoord;
uniform sampler2D sTexture;
uniform sampler2D uHistory0;
uniform vec2 uTexelSize;
uniform float uBlur;
uniform float uBlend;
uniform float uDirection;
uniform float uTimes;
uniform float uHistoryCount;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

// Estimate the displacement of a moving detail from the previous frame
// into the current frame. The returned vector is in pixel units.
// Lucas-Kanade flow with a texture-structure confidence score.
// Flat areas and single-direction edges are rejected: their motion estimate
// is ambiguous and was the main source of patchy/blotchy trails.
// One Lucas-Kanade refinement level. stepPixels controls the spacing
// between gradient samples; initialFlow warps the previous frame before
// solving the residual displacement at this scale.
vec3 estimateFlowLevel(vec2 uv, sampler2D previousFrame, vec2 initialFlow, float stepPixels) {
    float xx=0.0, yy=0.0, xy=0.0, xt=0.0, yt=0.0;
    for (int y=-2; y<=2; y++) {
        for (int x=-2; x<=2; x++) {
            vec2 delta=vec2(float(x),float(y))*stepPixels*uTexelSize;
            vec2 p=clamp(uv+delta,uTexelSize*2.0,vec2(1.0)-uTexelSize*2.0);
            float l=luma(texture2D(sTexture,p-vec2(stepPixels*uTexelSize.x,0.0)).rgb);
            float r=luma(texture2D(sTexture,p+vec2(stepPixels*uTexelSize.x,0.0)).rgb);
            float d=luma(texture2D(sTexture,p-vec2(0.0,stepPixels*uTexelSize.y)).rgb);
            float u=luma(texture2D(sTexture,p+vec2(0.0,stepPixels*uTexelSize.y)).rgb);
            float now=luma(texture2D(sTexture,p).rgb);
            vec2 previousUv=clamp(p-initialFlow*uTexelSize,0.0,1.0);
            float prev=luma(texture2D(previousFrame,previousUv).rgb);
            float invStep=0.5/stepPixels;
            float gx=(r-l)*invStep;
            float gy=(u-d)*invStep;
            float gt=now-prev;
            xx+=gx*gx;
            yy+=gy*gy;
            xy+=gx*gy;
            xt+=gx*gt;
            yt+=gy*gt;
        }
    }

    float trace=xx+yy;
    float discriminant=sqrt(max((xx-yy)*(xx-yy)+4.0*xy*xy,0.0));
    float minEigen=0.5*(trace-discriminant);
    float structureConfidence=smoothstep(0.00020,0.0035,minEigen);

    float a=xx+0.00001;
    float c=yy+0.00001;
    float det=a*c-xy*xy;
    vec2 correction=vec2(0.0);
    if(det>1.0e-10) {
        // Positive displacement maps the previous-frame feature to its
        // current-frame position. The previous implementation inverted this
        // sign, making the sampling direction unstable.
        correction.x=(c*xt-xy*yt)/det;
        correction.y=(-xy*xt+a*yt)/det;
    }
    float correctionLength=length(correction);
    if(correctionLength>20.0) correction*=20.0/correctionLength;
    return vec3(correction,structureConfidence);
}

// Coarse-to-fine flow: large sampling spacing captures fast game motion,
// then progressively smaller levels refine edges and local object detail.
vec3 estimateFlow(vec2 uv, sampler2D previousFrame) {
    vec3 coarse=estimateFlowLevel(uv,previousFrame,vec2(0.0),4.0);
    vec3 medium=estimateFlowLevel(uv,previousFrame,coarse.xy,2.0);
    vec3 fine=estimateFlowLevel(uv,previousFrame,coarse.xy+medium.xy,1.0);
    vec2 flow=coarse.xy+medium.xy+fine.xy;
    float magnitude=length(flow);
    if(magnitude>48.0) flow*=48.0/magnitude;

    vec2 warped=clamp(uv-flow*uTexelSize,0.0,1.0);
    float nowCenter=luma(texture2D(sTexture,uv).rgb);
    float previousWarped=luma(texture2D(previousFrame,warped).rgb);
    float residual=abs(nowCenter-previousWarped);
    float consistencyConfidence=1.0-smoothstep(0.055,0.24,residual);
    float structureConfidence=max(coarse.z,max(medium.z,fine.z));
    return vec3(flow,structureConfidence*consistencyConfidence);
}
// Edge-aware vector smoothing. Neighbor vectors are blended only when
// they belong to a similarly lit region and point in a compatible direction.
// This reduces pixel-level flow noise without bleeding motion across object edges.
vec3 estimateSmoothedFlow(vec2 uv, sampler2D previousFrame) {
    vec3 center = estimateFlow(uv, previousFrame);
    float centerLuma = luma(texture2D(sTexture, uv).rgb);

    // Explicit constant offsets instead of an indexed local array.
    // Some GLES2 mobile shader compilers miscompile dynamic array indexing
    // in fragment shaders, even when the loop bounds are constant.
    vec2 weightedFlow = center.xy * 2.0;
    float weightedConfidence = center.z * 2.0;
    float totalWeight = 2.0;

    vec2 uvL = clamp(uv + vec2(-2.0, 0.0) * uTexelSize,
                     uTexelSize * 2.0, vec2(1.0) - uTexelSize * 2.0);
    vec3 flowL = estimateFlow(uvL, previousFrame);
    float lumL = luma(texture2D(sTexture, uvL).rgb);
    float edgeL = 1.0 - smoothstep(0.07, 0.24, abs(lumL - centerLuma));
    float motionL = 1.0 - smoothstep(3.0, 14.0, length(flowL.xy - center.xy));
    float weightL = edgeL * motionL * mix(0.25, 1.0, flowL.z);
    weightedFlow += flowL.xy * weightL;
    weightedConfidence += flowL.z * weightL;
    totalWeight += weightL;

    vec2 uvR = clamp(uv + vec2(2.0, 0.0) * uTexelSize,
                     uTexelSize * 2.0, vec2(1.0) - uTexelSize * 2.0);
    vec3 flowR = estimateFlow(uvR, previousFrame);
    float lumR = luma(texture2D(sTexture, uvR).rgb);
    float edgeR = 1.0 - smoothstep(0.07, 0.24, abs(lumR - centerLuma));
    float motionR = 1.0 - smoothstep(3.0, 14.0, length(flowR.xy - center.xy));
    float weightR = edgeR * motionR * mix(0.25, 1.0, flowR.z);
    weightedFlow += flowR.xy * weightR;
    weightedConfidence += flowR.z * weightR;
    totalWeight += weightR;

    vec2 uvU = clamp(uv + vec2(0.0, -2.0) * uTexelSize,
                     uTexelSize * 2.0, vec2(1.0) - uTexelSize * 2.0);
    vec3 flowU = estimateFlow(uvU, previousFrame);
    float lumU = luma(texture2D(sTexture, uvU).rgb);
    float edgeU = 1.0 - smoothstep(0.07, 0.24, abs(lumU - centerLuma));
    float motionU = 1.0 - smoothstep(3.0, 14.0, length(flowU.xy - center.xy));
    float weightU = edgeU * motionU * mix(0.25, 1.0, flowU.z);
    weightedFlow += flowU.xy * weightU;
    weightedConfidence += flowU.z * weightU;
    totalWeight += weightU;

    vec2 uvD = clamp(uv + vec2(0.0, 2.0) * uTexelSize,
                     uTexelSize * 2.0, vec2(1.0) - uTexelSize * 2.0);
    vec3 flowD = estimateFlow(uvD, previousFrame);
    float lumD = luma(texture2D(sTexture, uvD).rgb);
    float edgeD = 1.0 - smoothstep(0.07, 0.24, abs(lumD - centerLuma));
    float motionD = 1.0 - smoothstep(3.0, 14.0, length(flowD.xy - center.xy));
    float weightD = edgeD * motionD * mix(0.25, 1.0, flowD.z);
    weightedFlow += flowD.xy * weightD;
    weightedConfidence += flowD.z * weightD;
    totalWeight += weightD;

    return vec3(weightedFlow / max(totalWeight, 0.0001),
                weightedConfidence / max(totalWeight, 0.0001));
}

void main() {
    vec4 current = texture2D(sTexture, vTextureCoord);
    if (uBlur <= 0.001 || uBlend <= 0.001 || uHistoryCount < 1.0) {
        gl_FragColor = current;
        return;
    }

    // Estimate object movement from the previous frame, then use that vector
    // to integrate samples from the CURRENT image along the object's path.
    // This avoids stacking misregistered historical frames (the source of
    // vibrating patches and chunky ghost-like clumps).
    vec3 flowEstimate = estimateSmoothedFlow(vTextureCoord, uHistory0);
    vec2 flow = flowEstimate.xy;
    float flowLength = length(flow);
    float confidence = flowEstimate.z * smoothstep(0.10, 1.25, flowLength);

    if (confidence <= 0.008) {
        gl_FragColor = current;
        return;
    }

    float times = clamp(uTimes, 1.0, 6.0);
    float fastMotion = smoothstep(0.65, 4.0, flowLength);

    // Make blur grow aggressively with actual movement. At high speed,
    // fine texture should merge into a continuous directional smear.
    // "Kabur" controls spatial reach; "Times" multiplies the integration span.
    float blurReach = mix(0.85, 4.25, uBlur);
    float radiusPixels = flowLength * blurReach * times * 1.35;
    radiusPixels = clamp(radiusPixels, 0.0, 144.0);
    vec2 motionUv = normalize(flow + vec2(0.00001)) * radiusPixels * uTexelSize;

    vec3 streakColor = vec3(0.0);
    float totalWeight = 0.0;

    // 48 evenly distributed taps avoid visible separated blobs and produce
    // a smoother continuous streak even on fast, high-detail gameplay.
    for (int i = 0; i < 48; i++) {
        float t = (float(i) + 0.5) / 48.0;
        float position;
        float weight;

        if (uDirection < 0.5) {
            position = t * 2.0 - 1.0;
            weight = exp(-1.65 * position * position);
        } else if (uDirection < 1.5) {
            position = -t;
            weight = exp(-1.10 * t);
        } else {
            position = t;
            weight = exp(-1.10 * t);
        }

        vec2 sampleUv = clamp(
            vTextureCoord + motionUv * position,
            uTexelSize * 0.5,
            vec2(1.0) - uTexelSize * 0.5
        );
        vec3 sampleColor = texture2D(sTexture, sampleUv).rgb;
        streakColor += sampleColor * weight;
        totalWeight += weight;
    }

    streakColor /= max(totalWeight, 0.0001);

    // Blend remains independently adjustable. Faster movement and higher
    // blur strength suppress more of the original sharp detail.
    float detailLoss = smoothstep(0.75, 6.0, flowLength) * uBlur;
    float motionStrength = mix(0.78, 1.0, max(fastMotion, detailLoss));
    float strength = clamp(uBlend * confidence * motionStrength, 0.0, 1.0);
    gl_FragColor = vec4(mix(current.rgb, streakColor, strength), current.a);
}
