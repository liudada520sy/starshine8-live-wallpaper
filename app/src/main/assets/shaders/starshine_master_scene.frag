#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;

in vec2 vUv;
out vec4 fragColor;

// 核心纹理槽位：
// Slot 0: Phase A 2560x1600 静态主图
// Slot 1: Phase A 640x400 8-bit 灰度深度图
// Slot 2: Phase B MediaCodec/SurfaceTexture OES 零拷贝硬件解码视频流 (DMA-BUF)
uniform sampler2D uTextureColor;
uniform sampler2D uTextureDepth;
uniform samplerExternalOES uTextureVideoOes;
uniform mat4 uVideoTexMatrix;       // SurfaceTexture.getTransformMatrix() 硬件裁剪与 Y 轴校正矩阵

// 时间与当前视口实时分辨率（支持全屏 2560x1600 及 Flyme Auto 2/3、1/3 分屏自适应）
uniform float uTime;
uniform vec2 uResolution;

// 主视觉模式：0=EASTERN_RIPPLE, 1=PARALLAX_3D, 2=HYBRID_RIPPLE_PARALLAX, 3=PHASE_B_HARDWARE_VIDEO
uniform int uBaseMode;

// 车载三级温控状态：0=满血60fps双层粒子, 1=中温30fps单层粒子节能态, 2=高温静态保护态
uniform int uThermalTier;

// ADR-001 艺术参数与安全钳制参数
uniform float uParallaxMaxOffset;     // 内部强制 clamp(val, 0.018, 0.028)
uniform float uRippleHorizonY;        // 默认 0.72（画面下方 28% 水波折射区）
uniform float uTyndallRayIntensity;   // 默认 0.15（范围 0.12 ~ 0.18）
uniform vec2 uTouchRippleCenter;      // 归一化交互触点坐标 (0..1)
uniform float uTouchRippleAge;        // 触点涟漪衰减时间 (秒)

// 从 manifest.json 提取的 Palette 色彩向量
uniform vec3 uColorDominant;
uniform vec3 uColorVibrant;
uniform vec3 uColorLightVibrant;

// 氛围叠加层控制：32 频段音频律动 + 节气微粒流转
uniform int uEnableAudioOverlay;      // 0=关闭, 1=开启
uniform float uAudioBins[32];         // 32 频段归一化 FFT/呼吸包络能量 (0.0..1.0)
uniform int uParticlePreset;          // 0=NONE, 1=GOLD_DUST, 2=CHERRY_BLOSSOM, 3=MISTY_RAIN, 4=SNOWFLAKE

// 行车安全与夜间防眩光系数（日间 1.0，夜间开启大灯平滑过渡至 0.65）
uniform float uNightDimmingFactor;

// Flyme Auto 分屏/小窗（Split-Screen）焦点居中 Aspect-Fill UV 自适应重映射
vec2 remapSplitScreenAspectFillUv(vec2 rawUv, vec2 viewportRes) {
    float targetAspect = 2560.0 / 1600.0; // 1.6 (16:10)
    float viewAspect = max(viewportRes.x, 1.0) / max(viewportRes.y, 1.0);
    vec2 scale = vec2(1.0);
    if (viewAspect > targetAspect) {
        scale.y = targetAspect / viewAspect;
    } else if (viewAspect < targetAspect) {
        scale.x = viewAspect / targetAspect;
    }
    return (rawUv - 0.5) * scale + 0.5;
}

float hash21(vec2 p) {
    p = fract(p * vec2(234.34, 435.345));
    p += dot(p, p + 34.23);
    return fract(p.x * p.y);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm2Octave(vec2 p) {
    float v = 0.0;
    v += 0.65 * valueNoise(p);
    v += 0.35 * valueNoise(p * 2.03 + vec2(17.1, 31.7));
    return v;
}

float computeLuma(vec3 rgb) {
    return dot(rgb, vec3(0.2126, 0.7152, 0.0722));
}

// 5-Tap 跨双边联合边缘柔化深度采样（5-Tap Cross-Bilateral Joint Depth Filter）
float sampleSmoothedDepth(vec2 uv, out float outEdgeGradient) {
    vec2 texel = vec2(1.0 / 640.0, 1.0 / 400.0) * 1.35;
    float dCenter = texture(uTextureDepth, uv).r;
    float lumaCenter = computeLuma(texture(uTextureColor, uv).rgb);

    vec2 uvL = clamp(uv - vec2(texel.x, 0.0), vec2(0.001), vec2(0.999));
    vec2 uvR = clamp(uv + vec2(texel.x, 0.0), vec2(0.001), vec2(0.999));
    vec2 uvU = clamp(uv - vec2(0.0, texel.y), vec2(0.001), vec2(0.999));
    vec2 uvD = clamp(uv + vec2(0.0, texel.y), vec2(0.001), vec2(0.999));

    float dL = texture(uTextureDepth, uvL).r;
    float dR = texture(uTextureDepth, uvR).r;
    float dU = texture(uTextureDepth, uvU).r;
    float dD = texture(uTextureDepth, uvD).r;

    float lumaL = computeLuma(texture(uTextureColor, uvL).rgb);
    float lumaR = computeLuma(texture(uTextureColor, uvR).rgb);
    float lumaU = computeLuma(texture(uTextureColor, uvU).rgb);
    float lumaD = computeLuma(texture(uTextureColor, uvD).rgb);

    float sigmaDepth = 0.11;
    float sigmaLuma = 0.18;

    float wL = exp(-abs(dL - dCenter) / sigmaDepth - abs(lumaL - lumaCenter) / sigmaLuma) * 0.22;
    float wR = exp(-abs(dR - dCenter) / sigmaDepth - abs(lumaR - lumaCenter) / sigmaLuma) * 0.22;
    float wU = exp(-abs(dU - dCenter) / sigmaDepth - abs(lumaU - lumaCenter) / sigmaLuma) * 0.22;
    float wD = exp(-abs(dD - dCenter) / sigmaDepth - abs(lumaD - lumaCenter) / sigmaLuma) * 0.22;
    float wC = max(0.12, 1.0 - (wL + wR + wU + wD));

    float normSum = wC + wL + wR + wU + wD;
    float smoothed = (dCenter * wC + dL * wL + dR * wR + dU * wU + dD * wD) / normSum;

    outEdgeGradient = clamp(length(vec2(dR - dL, dD - dU)) * 3.2, 0.0, 1.0);
    return clamp(smoothed, 0.0, 1.0);
}

// 45° 深度遮挡感知丁达尔体积晨光（Depth-Aware FBM Crepuscular Volumetric God-Rays）
vec3 computeTyndallLight(vec2 uv, float sceneDepth, float intensity) {
    float clampedIntensity = clamp(intensity, 0.12, 0.18);
    vec2 sunOrigin = vec2(0.14, -0.12);
    vec2 delta = uv - sunOrigin;
    float dist = length(delta);
    float angle = atan(delta.y, delta.x);

    float shaftPrimary = sin(angle * 15.0 + uTime * 0.20) * 0.5 + 0.5;
    float shaftSecondary = sin(angle * 27.0 - uTime * 0.14 + 1.2) * 0.5 + 0.5;
    float atmosphericDust = fbm2Octave(uv * vec2(5.5, 3.5) + vec2(-uTime * 0.08, uTime * 0.05));
    float volumetricBeam = pow(shaftPrimary * shaftSecondary, 1.85) * (0.65 + 0.35 * atmosphericDust);

    float spatialFade = smoothstep(1.35, 0.08, dist) * smoothstep(0.96, 0.04, uv.y);
    float depthOcclusion = mix(1.0, 0.58, smoothstep(0.65, 0.95, sceneDepth));

    vec3 rayTint = mix(uColorLightVibrant, uColorVibrant, 0.38);
    return rayTint * volumetricBeam * spatialFade * depthOcclusion * clampedIntensity * 1.15;
}

// 江南烟雨（Preset 3）专属：雨滴落在水面区域激起的同心微涟漪法线扰动
vec2 computeRaindropWaterRipples(vec2 uv, float waterRatio) {
    vec2 aspectUv = vec2(uv.x * (max(uResolution.x, 1.0) / max(uResolution.y, 1.0)), uv.y);
    vec2 gridUv = aspectUv * vec2(12.0, 18.0);
    vec2 cellId = floor(gridUv);
    vec2 localUv = fract(gridUv) - 0.5;

    float seed = hash21(cellId);
    if (seed < 0.45) return vec2(0.0);

    float cycle = fract(uTime * (0.85 + seed * 0.45) + seed * 7.13);
    float radius = cycle * 0.42;
    float dist = length(localUv);
    float ring = exp(-pow((dist - radius) * 22.0, 2.0)) * (1.0 - cycle);
    float wave = sin((dist - radius) * 75.0) * ring;
    return normalize(localUv + vec2(0.0001)) * wave * 0.0022 * waterRatio;
}

// 四大节气程序化 SDF 微粒图层（支持温控 Tier 动态减层、六角冰晶 SDF 与音频能量微联动）
vec3 computeSolarTermParticles(vec2 uv, int preset, int thermalTier, float bassEnergy) {
    if (preset == 0 || thermalTier >= 2) {
        return vec3(0.0);
    }
    float aspect = max(uResolution.x, 1.0) / max(uResolution.y, 1.0);
    vec2 aspectUv = vec2(uv.x * aspect, uv.y);
    vec3 accum = vec3(0.0);

    if (preset == 3) {
        int rainLayers = (thermalTier == 0) ? 2 : 1;
        for (int rl = 0; rl < 2; ++rl) {
            if (rl >= rainLayers) break;
            float layerScale = (rl == 0) ? 1.0 : 1.55;
            vec2 rainUv = (aspectUv + vec2(uv.y * 0.16, 0.0)) * vec2(26.0, 5.5) * layerScale +
                          vec2(uTime * 0.45, -uTime * (3.6 + float(rl) * 1.1));
            vec2 cell = floor(rainUv);
            vec2 local = fract(rainUv);
            float rnd = hash21(cell + float(rl) * 13.7);
            if (rnd > 0.60) {
                float line = smoothstep(0.075, 0.0, abs(local.x - 0.5)) *
                             smoothstep(0.0, 0.22, local.y) *
                             smoothstep(1.0, 0.42, local.y);
                float alpha = (rl == 0) ? 0.22 : 0.13;
                accum += mix(uColorLightVibrant, vec3(0.84, 0.91, 0.97), 0.65) * line * alpha;
            }
        }
        float mistBand = exp(-pow((uv.y - uRippleHorizonY) * 6.5, 2.0));
        float mistNoise = fbm2Octave(vec2(uv.x * 3.5 + uTime * 0.06, uv.y * 6.0));
        accum += mix(uColorDominant, uColorLightVibrant, 0.5) * mistBand * mistNoise * 0.06;
        return accum;
    }

    int maxLayers = (thermalTier == 0) ? 2 : 1;
    for (int layer = 0; layer < 2; ++layer) {
        if (layer >= maxLayers) break;
        float scale = (layer == 0) ? 8.5 : 13.5;
        float speedY = (preset == 1) ? -0.16 : 0.26;
        vec2 drift = vec2(
            sin(uTime * 0.24 + float(layer) * 1.7) * 0.36,
            uTime * speedY * (1.0 + float(layer) * 0.38)
        );
        vec2 gridUv = aspectUv * scale + drift;
        vec2 cellId = floor(gridUv);
        vec2 cellUv = fract(gridUv) - 0.5;

        float seed = hash21(cellId + float(layer) * 19.7);
        if (seed > 0.54) {
            vec2 jitter = vec2(fract(seed * 13.3) - 0.5, fract(seed * 29.1) - 0.5) * 0.44;
            vec2 delta = cellUv - jitter;
            float dist = length(delta);

            if (preset == 1) {
                float pulse = 0.55 + 0.45 * sin(uTime * 2.2 + seed * 6.2831) + bassEnergy * 0.22;
                float core = smoothstep(0.06, 0.0, dist);
                float halo = smoothstep(0.18, 0.01, dist);
                accum += (uColorLightVibrant * core * 0.45 + mix(uColorVibrant, uColorLightVibrant, 0.55) * halo * 0.28) * pulse;
            } else if (preset == 2) {
                float angle = uTime * 0.85 + seed * 6.2831;
                mat2 rot = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));
                vec2 p = rot * delta;
                float tilt = 1.35 + 0.55 * sin(uTime * 1.4 + seed * 11.0);
                float petalDist = length(vec2(p.x * tilt, p.y));
                float notch = 0.018 * max(0.0, cos(atan(p.y, p.x) * 2.0));
                float petal = smoothstep(0.135, 0.035, petalDist - notch);
                vec3 sakuraColor = mix(vec3(1.0, 0.83, 0.89), uColorLightVibrant, 0.32);
                accum += sakuraColor * petal * 0.29;
            } else if (preset == 4) {
                float angle = atan(delta.y, delta.x) + uTime * 0.35;
                float hexRay = 0.78 + 0.22 * cos(angle * 6.0);
                float crystal = smoothstep(0.13 * hexRay, 0.015, dist);
                accum += vec3(0.93, 0.97, 1.0) * crystal * 0.28;
            }
        }
    }
    return accum;
}

// 底部 32 频段 Flyme Sound 流光音乐律动波浪
vec3 computeAudioSpectrumRibbon(vec2 uv) {
    if (uEnableAudioOverlay == 0 || uv.y < 0.80) {
        return vec3(0.0);
    }
    float xScaled = clamp(uv.x, 0.0, 0.9999) * 31.0;
    int idx0 = int(floor(xScaled));
    int idx1 = min(idx0 + 1, 31);
    float fracX = fract(xScaled);
    float energy = mix(uAudioBins[idx0], uAudioBins[idx1], smoothstep(0.0, 1.0, fracX));

    float baselineY = 0.975;
    float waveHeight = clamp(energy, 0.02, 1.0) * 0.11;
    float topEdgeY = baselineY - waveHeight;

    float distToTop = abs(uv.y - topEdgeY);
    float crestLine = smoothstep(0.006, 0.0008, distToTop);
    float fillMask = smoothstep(topEdgeY - 0.004, baselineY, uv.y) * step(topEdgeY, uv.y);

    vec3 ribbonColor = mix(uColorVibrant, uColorLightVibrant, crestLine);
    return ribbonColor * (crestLine * 0.55 + fillMask * 0.16);
}

void main() {
    vec2 baseUv = remapSplitScreenAspectFillUv(vUv, uResolution);
    vec2 sampleUv = baseUv;

    float edgeGrad = 0.0;
    float smoothedDepth = sampleSmoothedDepth(sampleUv, edgeGrad);
    float bassEnergy = (uEnableAudioOverlay == 1) ? clamp((uAudioBins[1] + uAudioBins[2] + uAudioBins[3]) * 0.333, 0.0, 1.0) : 0.0;

    if (uBaseMode == 1 || uBaseMode == 2) {
        float safeMaxOffset = clamp(uParallaxMaxOffset, 0.018, 0.028);
        float centeredDepth = smoothedDepth - 0.5;
        vec2 lissajousOrbit = vec2(
            sin(uTime * 0.65) * 0.75 + sin(uTime * 0.31) * 0.25,
            cos(uTime * 0.48) * 0.70 + cos(uTime * 0.23) * 0.30
        );
        vec2 edgeGuard = smoothstep(vec2(0.0), vec2(0.06), sampleUv) *
                         smoothstep(vec2(1.0), vec2(0.94), sampleUv);
        float silhouetteGuard = 1.0 - edgeGrad * 0.55;
        sampleUv += lissajousOrbit * safeMaxOffset * centeredDepth * edgeGuard * silhouetteGuard;
    }

    float rippleHighlight = 0.0;
    float waterFresnelMask = 0.0;
    if (uBaseMode == 0 || uBaseMode == 2) {
        float horizon = clamp(uRippleHorizonY, 0.50, 0.90);
        if (sampleUv.y > horizon) {
            float waterRatio = (sampleUv.y - horizon) / max(1.0 - horizon, 0.001);
            float shoreFade = smoothstep(0.0, 0.14, waterRatio);

            float waveFreqY = mix(26.0, 76.0, pow(1.0 - waterRatio, 1.35));
            float phase1 = sampleUv.y * waveFreqY - uTime * (2.05 + bassEnergy * 0.35) + sampleUv.x * 9.0;
            float phase2 = sampleUv.y * (waveFreqY * 1.42) - uTime * 1.55 - sampleUv.x * 14.0;
            float organicNoise = fbm2Octave(sampleUv * vec2(16.0, 28.0) + vec2(uTime * 0.28, -uTime * 0.38));

            float dx = (sin(phase1 + organicNoise * 2.8) * 0.0042 + sin(phase2) * 0.0018) * waterRatio * shoreFade;
            float dy = (cos(phase1 * 0.85) * 0.0022 + cos(phase2 * 1.15) * 0.0010) * waterRatio * shoreFade;

            if (uParticlePreset == 3 && uThermalTier < 2) {
                vec2 rainRipple = computeRaindropWaterRipples(sampleUv, waterRatio * shoreFade);
                dx += rainRipple.x;
                dy += rainRipple.y;
            }

            sampleUv += vec2(dx, dy);

            vec3 waterNormal = normalize(vec3(-dx * 85.0, -dy * 85.0, 1.0));
            vec3 viewDir = normalize(vec3(0.0, 0.35 + waterRatio * 0.45, 0.82));
            float fresnel = pow(1.0 - clamp(dot(waterNormal, viewDir), 0.0, 1.0), 3.5);
            waterFresnelMask = fresnel * shoreFade * 0.24;

            float crestGlint = pow(max(0.0, sin(phase1) * 0.65 + sin(phase2) * 0.35), 3.0);
            rippleHighlight = crestGlint * waterRatio * shoreFade * 0.095;
        }

        if (uTouchRippleAge >= 0.0 && uTouchRippleAge < 3.5) {
            vec2 aspectDelta = (sampleUv - uTouchRippleCenter) * vec2(max(uResolution.x, 1.0) / max(uResolution.y, 1.0), 1.0);
            float dist = length(aspectDelta);
            float radius = uTouchRippleAge * 0.28;
            float envelope = exp(-pow((dist - radius) * 18.0, 2.0)) * exp(-uTouchRippleAge * 1.12);
            float wave = (sin((dist - radius) * 65.0) + 0.45 * sin((dist - radius) * 115.0)) * envelope;
            sampleUv += normalize(aspectDelta + vec2(0.0001)) * wave * 0.0075;
            rippleHighlight += max(0.0, wave) * 0.13;
        }
    }

    sampleUv = clamp(sampleUv, vec2(0.001), vec2(0.999));

    // Phase B 零拷贝 OES 视频纹理分支（经 SurfaceTexture.getTransformMatrix() 校正方向与对齐裁剪）
    vec3 baseColor;
    if (uBaseMode == 3) {
        vec2 videoUv = (uVideoTexMatrix * vec4(sampleUv, 0.0, 1.0)).xy;
        baseColor = texture(uTextureVideoOes, clamp(videoUv, vec2(0.001), vec2(0.999))).rgb;
    } else {
        baseColor = texture(uTextureColor, sampleUv).rgb;
        if (waterFresnelMask > 0.001) {
            vec3 skyReflectionTint = mix(uColorDominant, uColorLightVibrant, 0.38);
            baseColor = mix(baseColor, skyReflectionTint, waterFresnelMask);
        }
        baseColor += uColorLightVibrant * rippleHighlight;
        vec3 tyndall = computeTyndallLight(baseUv, smoothedDepth, uTyndallRayIntensity);
        baseColor = 1.0 - (1.0 - baseColor) * (1.0 - tyndall);
    }

    baseColor += computeSolarTermParticles(vUv, uParticlePreset, uThermalTier, bassEnergy);
    baseColor += computeAudioSpectrumRibbon(vUv);

    float safeNightDim = clamp(uNightDimmingFactor, 0.50, 1.0);
    float luminance = computeLuma(baseColor);
    if (safeNightDim < 0.99 && luminance > 0.85) {
        float excess = luminance - 0.85;
        baseColor *= (0.85 + excess * 0.65) / max(luminance, 0.0001);
    }
    baseColor *= safeNightDim;

    fragColor = vec4(clamp(baseColor, 0.0, 1.0), 1.0);
}
