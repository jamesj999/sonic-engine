// VHS picture-search (rewind/review) post-process.
// Applied by RewindVhsEffectPass while live rewind is active; runs before any
// user display shader so a CRT preset displays the damaged "signal".
// Simulates the tape, not the TV: no scanlines, no curvature.

uniform sampler2D Texture;
uniform vec2 TextureSize;
uniform vec2 OutputSize;
uniform int FrameCount;
uniform float RewindIntensity; // 0..1 envelope
uniform float RewindSpeed;     // 0.25..4.0, 1.0 = base tape speed
uniform float RewindScroll;    // band scroll phase 0..1, integrated CPU-side so a
                               // speed change alters the scroll rate, not the position
uniform float RewindTearBands; // 1.0 = tear bands on, 0.0 = bands (and their tear
                               // lines / in-band noise) disabled; other layers remain

float hash21(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// shortest distance between two points on the 0..1 wrap-around ring
float ringDist(float a, float b) {
    float d = abs(a - b);
    return min(d, 1.0 - d);
}

void main() {
    vec2 uv = gl_FragCoord.xy / OutputSize;
    float t = float(FrameCount);
    float k = clamp(RewindIntensity, 0.0, 1.0);
    float speed = clamp(RewindSpeed, 0.25, 4.0);

    // vertical wobble: slow sub-pixel sway
    uv.y += k * (0.5 / TextureSize.y) * sin(t * 0.11 + uv.x * 3.0);

    float line = floor(uv.y * TextureSize.y);

    // picture-search tear bands: 2 wide noise bands, plus a 3rd whose amplitude
    // fades in as tape speed approaches double (no pop at the speed crossover);
    // all scroll upward at the CPU-integrated phase (rate follows tape speed)
    float scroll = fract(RewindScroll);
    float thirdBand = smoothstep(1.2, 2.0, speed);
    float band = 0.0;
    for (int i = 0; i < 3; i++) {
        float amp = i == 2 ? thirdBand : 1.0;
        if (amp <= 0.0) {
            continue;
        }
        float center = fract(float(i) / 3.0 + scroll);
        float d = ringDist(uv.y, center);
        band = max(band, amp * (1.0 - smoothstep(0.035, 0.05, d)));
    }
    band *= k * clamp(RewindTearBands, 0.0, 1.0);

    // ragged per-line horizontal displacement inside the bands (up to ~14 src px)
    float rag = hash21(vec2(line, t)) * 2.0 - 1.0;
    float x = uv.x + band * rag * (14.0 / TextureSize.x);

    // global per-scanline jitter (+/- 1.5 src px)
    float jitter = hash21(vec2(line * 1.37, t * 0.7)) * 2.0 - 1.0;
    x += k * jitter * (1.5 / TextureSize.x);

    // head-switching strip: bottom ~2.5% of the frame
    float strip = 1.0 - smoothstep(0.02, 0.03, uv.y);
    x += strip * k * (hash21(vec2(line, t * 1.3)) - 0.3) * (18.0 / TextureSize.x);

    vec2 suv = vec2(x, uv.y);

    // chroma fringing: R and B sampled with opposite ~1.5 px offsets
    float fringe = k * 1.5 / TextureSize.x;
    vec3 col;
    col.r = texture2D(Texture, suv + vec2(fringe, 0.0)).r;
    col.g = texture2D(Texture, suv).g;
    col.b = texture2D(Texture, suv - vec2(fringe, 0.0)).b;

    // global tape desaturation (~12%)
    float luma = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(col, vec3(luma), 0.12 * k);

    // inside bands / head-switch strip: mute (not kill) chroma, mix in luma static
    float noiseZone = max(band, strip * k);
    float staticN = hash21(vec2(floor(suv.x * TextureSize.x), line + t * 13.0));
    col = mix(col, vec3(luma), noiseZone * 0.7);
    col = mix(col, vec3(staticN), noiseZone * 0.25);

    // bright tear line at band edges (band field transitions 0 -> 1)
    float tear = smoothstep(0.15, 0.25, band * (1.0 - band)) * k;
    col += tear * vec3(0.22);

    // tape dropouts: sparse bright horizontal dashes
    float rowRoll = hash21(vec2(line, floor(t * 0.5)));
    if (rowRoll > 0.997) {
        float segStart = hash21(vec2(line * 3.1, floor(t * 0.5)));
        float segLen = 0.04 + 0.08 * hash21(vec2(line * 7.7, t));
        if (uv.x > segStart && uv.x < segStart + segLen) {
            col = mix(col, vec3(0.95), k * 0.85);
        }
    }

    gl_FragColor = vec4(col, 1.0);
}
