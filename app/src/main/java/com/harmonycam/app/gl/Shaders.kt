package com.harmonycam.app.gl

object Shaders {

    const val QUAD_VS = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    /** Camera → graded frame: chromatic aberration, colour grade, audio-reactive vignette, grain. */
    const val CAMERA_FS = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vUv;
uniform samplerExternalOES uTex;
uniform mat4 uStMatrix;
uniform mat3 uUvMap;
uniform float uTime;
uniform float uLevel;
uniform float uFx;
uniform float uChroma;
uniform float uSat;
uniform float uContrast;
uniform float uVignette;
uniform float uGrade;
uniform vec3 uShadow;
uniform vec3 uHighlight;

vec3 cam(vec2 uv) {
    vec3 b = uUvMap * vec3(uv, 1.0);
    vec2 t = (uStMatrix * vec4(b.xy, 0.0, 1.0)).xy;
    return texture2D(uTex, t).rgb;
}
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

void main() {
    vec2 c = vUv - 0.5;
    float ca = uChroma * (0.0015 + 0.006 * uLevel + 0.004 * uFx);
    vec3 col;
    col.r = cam(vUv + c * ca).r;
    col.g = cam(vUv).g;
    col.b = cam(vUv - c * ca).b;

    float l = dot(col, vec3(0.299, 0.587, 0.114));
    vec3 graded = mix(vec3(l), col, uSat);
    graded = (graded - 0.5) * uContrast + 0.5;
    // split toning: shadows and highlights pushed towards the style colours
    graded += uShadow * (1.0 - smoothstep(0.0, 0.55, l)) * 0.12;
    graded += uHighlight * smoothstep(0.45, 1.0, l) * 0.10;
    col = mix(col, graded, uGrade);

    float vig = smoothstep(0.95, 0.25, length(c * vec2(1.0, 0.85)));
    col *= mix(1.0, vig, uVignette * (0.75 + 0.25 * uLevel));
    col += (hash(vUv * 731.0 + uTime) - 0.5) * 0.03 * uGrade;
    gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
"""

    const val POINT_VS = """
attribute vec2 aPos;
attribute float aSize;
attribute vec4 aColor;
varying vec4 vColor;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    gl_PointSize = aSize;
    vColor = aColor;
}
"""

    /** Soft glowing sprite with a bright core. Additive blending. */
    const val POINT_FS = """
precision mediump float;
varying vec4 vColor;
void main() {
    vec2 p = gl_PointCoord * 2.0 - 1.0;
    float d = dot(p, p);
    if (d > 1.0) discard;
    float halo = exp(-d * 3.5);
    float core = exp(-d * 28.0) * 0.8;
    vec3 rgb = vColor.rgb * halo + vec3(core);
    gl_FragColor = vec4(rgb * vColor.a, vColor.a * halo);
}
"""

    const val BLUR_FS = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uDir;
void main() {
    vec3 s = texture2D(uTex, vUv).rgb * 0.227027;
    s += texture2D(uTex, vUv + uDir * 1.3846).rgb * 0.316216;
    s += texture2D(uTex, vUv - uDir * 1.3846).rgb * 0.316216;
    s += texture2D(uTex, vUv + uDir * 3.2308).rgb * 0.070270;
    s += texture2D(uTex, vUv - uDir * 3.2308).rgb * 0.070270;
    gl_FragColor = vec4(s, 1.0);
}
"""

    /** Final composite: graded scene + bloom + audio-reactive light leak and pulse. */
    const val COMPOSITE_FS = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uScene;
uniform sampler2D uGlow;
uniform float uGlowAmt;
uniform float uTime;
uniform float uPulse;
uniform float uLeak;
uniform vec3 uLeakColor;
void main() {
    vec3 s = texture2D(uScene, vUv).rgb;
    vec3 g = texture2D(uGlow, vUv).rgb;
    vec3 col = s + g * uGlowAmt * (1.0 - 0.4 * s);
    vec2 lp = vec2(0.85 + 0.12 * sin(uTime * 0.27), 0.88 + 0.06 * cos(uTime * 0.19));
    float leak = max(0.0, 1.0 - length((vUv - lp) * vec2(1.0, 0.6)) * 1.7);
    col += uLeakColor * leak * leak * uLeak * (0.4 + uPulse);
    // gentle edge pulse on loud notes
    float edge = smoothstep(0.35, 0.75, length(vUv - 0.5));
    col += uLeakColor * edge * uPulse * 0.10 * uLeak;
    gl_FragColor = vec4(min(col, vec3(1.0)), 1.0);
}
"""

    const val BLIT_FS = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uScale;
uniform vec2 uOffset;
void main() {
    gl_FragColor = vec4(texture2D(uTex, vUv * uScale + uOffset).rgb, 1.0);
}
"""
}
