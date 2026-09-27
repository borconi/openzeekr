package com.openzeekr.app.ui

import androidx.compose.ui.graphics.Color

/**
 * Per-model paint palettes + white render. Zeekr publishes no official hex, so these
 * are sourced/estimated (see ZEEKR_COLORS.md). The colour tints the hero "identity
 * card"; the white render (already transparent) sits on top, loaded at runtime from
 * assets/cars/ (gitignored — those are Zeekr press renders; supply your own locally).
 * The app keys this off the vehicle-list `modelName`/`innerCode` + `colorName`.
 */
data class PaintColor(
    val name: String,
    val color: Color,
    val finish: String,
    /**
     * Optional per-channel diagonal recolour scale [rScale, gScale, bScale], sampled to match the
     * rendered body on the white studio card. When set, the hero applies this exact diagonal
     * ColorMatrix to the grayscale body mask (white → this colour, shadows preserved) instead of
     * deriving a luminance matrix from [color].
     */
    val recolor: FloatArray? = null,
)

data class CarModel(
    val key: String,
    val displayName: String,
    /** Asset path under assets/, e.g. "cars/car_7gt.webp" (may be absent → no render). */
    val renderAsset: String,
    val colors: List<PaintColor>,
    /**
     * Optional two-layer paint render (grayscale metallic body + separate details/trim/wheels).
     * When both are present the hero tints [bodyAsset] with the selected paint colour via a
     * MULTIPLY blend (so shadows/highlights survive) and draws [detailsAsset] untouched on top —
     * a live, per-colour photoreal paint job. Falls back to [renderAsset] when null.
     */
    val bodyAsset: String? = null,
    val detailsAsset: String? = null,
)

object CarCatalog {
    private fun c(rgb: Long) = Color(0xFF000000 or rgb)

    val models: List<CarModel> = listOf(
        CarModel(
            "001", "Zeekr 001", "cars/car_001.webp",
            listOf(
                PaintColor("Crystal White", c(0xDEDEDE), "Pearl"),
                PaintColor("Tech Grey", c(0x3B3B3E), "Metallic"),
                PaintColor("Phantom Black", c(0x2B2B2B), "Metallic"),
                PaintColor("Electric Blue", c(0x35AACB), "Metallic"),
                PaintColor("Energy Orange", c(0xFF7F00), "Metallic"),
                PaintColor("Forest Green", c(0x2E3B2E), "Metallic"),
                PaintColor("Mineral Green", c(0x5A6B5A), "Metallic"),
                PaintColor("Lava Grey", c(0x6E6F72), "Metallic"),
            )
        ),
        CarModel(
            "X", "Zeekr X", "cars/car_x.webp",
            listOf(
                PaintColor("Crystal White", c(0xF3F6FA), "Pearl"),
                PaintColor("Mist Grey", c(0x9A9CA0), "Metallic"),
                PaintColor("Grid Grey", c(0x65666A), "Metallic"),
                PaintColor("Palace Beige", c(0xE0D2AF), "Metallic"),
                PaintColor("Pine Green", c(0x3C4A32), "Metallic"),
                PaintColor("Matt Khaki Green", c(0x909602), "Matte"),
            )
        ),
        CarModel(
            "7X", "Zeekr 7X", "cars/car_7x.webp",
            listOf(
                PaintColor("Forest Green", c(0x436238), "Metallic"),
                PaintColor("Crystal White", c(0xBDC0C3), "Pearl"),
                PaintColor("Onyx Black", c(0x111111), "Metallic"),
                PaintColor("Tech Grey", c(0x868686), "Metallic"),
                PaintColor("Brookblue", c(0x677FA3), "Two-tone"),
            ),
            bodyAsset = "cars/7x_body.png", detailsAsset = "cars/7x_details.png"
        ),
        // 7GT: the six factory finishes with hexes calibrated against the studio renders (see the
        // luminance-recolour matrices), plus Mystic Lilac (the reference car). The hero generates the
        // recolour matrix from each hex at draw time.
        CarModel(
            "7GT", "Zeekr 7GT", "cars/car_7gt.webp",
            listOf(
                PaintColor("Mystic Lilac", c(0xB9A7C4), "Pearl", recolor = floatArrayOf(0.801f, 0.731f, 0.844f)),
                PaintColor("Crystal White", c(0xF0F1F3), "Pearl", recolor = floatArrayOf(0.94f, 0.95f, 0.96f)),
                PaintColor("Glacier Silver", c(0xCED3D9), "Metallic", recolor = floatArrayOf(0.81f, 0.83f, 0.86f)),
                PaintColor("Tech Grey", c(0x84878B), "Metallic", recolor = floatArrayOf(0.585f, 0.596f, 0.613f)),
                PaintColor("Titanium Grey", c(0x524E54), "Metallic", recolor = floatArrayOf(0.377f, 0.361f, 0.387f)),
                PaintColor("Onyx Black", c(0x1C1D20), "Metallic", recolor = floatArrayOf(0.140f, 0.145f, 0.162f)),
                PaintColor("Forest Green", c(0x2B4437), "Metallic", recolor = floatArrayOf(0.210f, 0.317f, 0.258f)),
            ),
            bodyAsset = "cars/7gt_body.png", detailsAsset = "cars/7gt_details.png"
        ),
        CarModel(
            "9X", "Zeekr 9X", "cars/car_9x.webp",
            listOf(
                PaintColor("Onyx Black", c(0x1A1A1A), "Metallic"),
                PaintColor("Crystal White", c(0xEDEFF2), "Pearl"),
                PaintColor("Lava Grey", c(0x6C6E71), "Metallic"),
                PaintColor("Glacier Silver", c(0xC6CACE), "Metallic"),
                PaintColor("Wilderness Green", c(0x4A5648), "Metallic"),
            )
        ),
    )

    private val byKey = models.associateBy { it.key }

    /** Resolve from a vehicle-list modelName / seriesName / innerCode (e.g. "CX1E" = 7X). */
    fun forModel(name: String?): CarModel {
        val n = name?.uppercase()?.replace(" ", "") ?: return byKey.getValue("7GT")
        return when {
            "7GT" in n || "007GT" in n -> byKey.getValue("7GT")
            "CX1E" in n || "7X" in n -> byKey.getValue("7X")
            "9X" in n -> byKey.getValue("9X")
            "001" in n -> byKey.getValue("001")
            n == "X" || "ZEEKRX" in n -> byKey.getValue("X")
            else -> byKey.getValue("7GT")
        }
    }

    /** Look up a colour by its reported name within a model (falls back to the first). */
    fun colorFor(model: CarModel, colorName: String?): PaintColor {
        if (colorName != null) {
            model.colors.firstOrNull { it.name.equals(colorName, ignoreCase = true) }?.let { return it }
        }
        return model.colors.first()
    }
}
