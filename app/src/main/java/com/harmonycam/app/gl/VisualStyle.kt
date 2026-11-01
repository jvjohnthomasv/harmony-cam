package com.harmonycam.app.gl

enum class VisualStyle(
    val label: String,
    val voiceColors: Array<FloatArray>,
    val shadow: FloatArray,
    val highlight: FloatArray,
    val leak: FloatArray,
    val saturation: Float,
    val contrast: Float,
    val vignette: Float,
    val chroma: Float,
    val grade: Float,
    val glow: Float,
    val dust: Int,
    val trails: Boolean,
) {
    NEON(
        "Neon",
        arrayOf(rgb(0.13f, 0.90f, 1.00f), rgb(1.00f, 0.31f, 0.85f), rgb(0.62f, 0.45f, 1.00f), rgb(0.50f, 1.00f, 0.45f)),
        rgb(0.10f, 0.20f, 0.65f), rgb(1.00f, 0.35f, 0.75f), rgb(1.00f, 0.25f, 0.75f),
        1.15f, 1.12f, 0.65f, 1.0f, 1.0f, 1.7f, 26, true
    ),
    AURORA(
        "Aurora",
        arrayOf(rgb(0.20f, 1.00f, 0.75f), rgb(0.45f, 1.00f, 0.40f), rgb(0.70f, 0.45f, 1.00f), rgb(0.30f, 0.60f, 1.00f)),
        rgb(0.05f, 0.30f, 0.35f), rgb(0.60f, 1.00f, 0.80f), rgb(0.35f, 1.00f, 0.70f),
        1.05f, 1.06f, 0.55f, 0.6f, 1.0f, 1.4f, 48, true
    ),
    GOLDEN(
        "Golden hour",
        arrayOf(rgb(1.00f, 0.75f, 0.30f), rgb(1.00f, 0.50f, 0.45f), rgb(1.00f, 0.92f, 0.70f), rgb(1.00f, 0.60f, 0.35f)),
        rgb(0.25f, 0.12f, 0.05f), rgb(1.00f, 0.80f, 0.50f), rgb(1.00f, 0.60f, 0.25f),
        0.95f, 1.08f, 0.70f, 0.4f, 1.0f, 1.25f, 34, true
    ),
    CLEAN(
        "Clean",
        arrayOf(rgb(0.85f, 0.95f, 1.00f), rgb(1.00f, 0.90f, 0.95f), rgb(0.90f, 0.90f, 1.00f), rgb(0.95f, 1.00f, 0.90f)),
        rgb(0f, 0f, 0f), rgb(0f, 0f, 0f), rgb(1f, 1f, 1f),
        1.0f, 1.0f, 0.15f, 0.0f, 0.0f, 0.8f, 0, false
    );
}

// top-level (not in the companion): enum entries are built before the companion object exists
private fun rgb(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)
