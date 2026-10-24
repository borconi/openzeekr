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
        CarModel("001", "Zeekr 001", "cars/car_001.webp", listOf(
            PaintColor("Crystal White", c(0xDEDEDE), "Pearl"),
            PaintColor("Tech Grey", c(0x3B3B3E), "Metallic"),
            PaintColor("Phantom Black", c(0x2B2B2B), "Metallic"),
            PaintColor("Electric Blue", c(0x35AACB), "Metallic"),
            PaintColor("Energy Orange", c(0xFF7F00), "Metallic"),
            PaintColor("Forest Green", c(0x2E3B2E), "Metallic"),
            PaintColor("Mineral Green", c(0x5A6B5A), "Metallic"),
            PaintColor("Lava Grey", c(0x6E6F72), "Metallic"),
        )),
        CarModel("X", "Zeekr X", "cars/car_x.webp", listOf(
            PaintColor("Crystal White", c(0xF3F6FA), "Pearl"),
            PaintColor("Mist Grey", c(0x9A9CA0), "Metallic"),
            PaintColor("Grid Grey", c(0x65666A), "Metallic"),
            PaintColor("Palace Beige", c(0xE0D2AF), "Metallic"),
            PaintColor("Pine Green", c(0x3C4A32), "Metallic"),
            PaintColor("Matt Khaki Green", c(0x909602), "Matte"),
        )),
        CarModel("7X", "Zeekr 7X", "cars/car_7x.webp", listOf(
            PaintColor("Forest Green", c(0x436238), "Metallic"),
            PaintColor("Crystal White", c(0xBDC0C3), "Pearl"),
            PaintColor("Onyx Black", c(0x111111), "Metallic"),
            PaintColor("Tech Grey", c(0x868686), "Metallic"),
            PaintColor("Brookblue", c(0x677FA3), "Two-tone"),
            PaintColor("Shadow Blue (including silver roof)", c(0x677FA3), "Two-tone"),
        ), bodyAsset = "cars/7x_body.png", detailsAsset = "cars/7x_details.png"),
        // 7GT: the six factory finishes with hexes calibrated against the studio renders (see the
        // luminance-recolour matrices), plus Mystic Lilac (the reference car). The hero generates the
        // recolour matrix from each hex at draw time.
        CarModel("7GT", "Zeekr 7GT", "cars/car_7gt.webp", listOf(
            PaintColor("Mystic Lilac", c(0xB9A7C4), "Pearl", recolor = floatArrayOf(0.801f, 0.731f, 0.844f)),
            PaintColor("Crystal White", c(0xF0F1F3), "Pearl", recolor = floatArrayOf(0.94f, 0.95f, 0.96f)),
            PaintColor("Glacier Silver", c(0xCED3D9), "Metallic", recolor = floatArrayOf(0.81f, 0.83f, 0.86f)),
            PaintColor("Tech Grey", c(0x84878B), "Metallic", recolor = floatArrayOf(0.585f, 0.596f, 0.613f)),
            PaintColor("Titanium Grey", c(0x524E54), "Metallic", recolor = floatArrayOf(0.377f, 0.361f, 0.387f)),
            PaintColor("Onyx Black", c(0x1C1D20), "Metallic", recolor = floatArrayOf(0.140f, 0.145f, 0.162f)),
            PaintColor("Forest Green", c(0x2B4437), "Metallic", recolor = floatArrayOf(0.210f, 0.317f, 0.258f)),
        ), bodyAsset = "cars/7gt_body.png", detailsAsset = "cars/7gt_details.png"),
        CarModel("9X", "Zeekr 9X", "cars/car_9x.webp", listOf(
            PaintColor("Onyx Black", c(0x1A1A1A), "Metallic"),
            PaintColor("Crystal White", c(0xEDEFF2), "Pearl"),
            PaintColor("Lava Grey", c(0x6C6E71), "Metallic"),
            PaintColor("Glacier Silver", c(0xC6CACE), "Metallic"),
            PaintColor("Wilderness Green", c(0x4A5648), "Metallic"),
        )),
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

    /** Neutral placeholder used when the reported colour can't be matched to a known paint. We render the
     *  car in a plain graphite instead of confidently showing the WRONG vivid paint (issue #8: a 7GT France
     *  car in Onyx Black was showing "Mystic Lilac", the 7GT palette's first entry). */
    private val NEUTRAL = c(0x6E7075)

    /** Normalise a colour name for tolerant matching: lower-case, strip spaces/hyphens and finish words. */
    private fun norm(s: String): String =
        s.lowercase().replace(Regex("[\\s\\-_]+"), "")
            .replace(Regex("(metallic|pearl|matte|matt|twotone|two-tone|colou?r)"), "")

    /** Localised / marketing colour-word aliases -> the English token used in our palette names, so a
     *  car whose backend returns a localised paint name (e.g. FR "Noir", DE "Schwarz", "Onyx") still
     *  resolves. Keyed on normalised tokens; values are tokens that appear in a [PaintColor.name]. */
    private val ALIASES: Map<String, String> = mapOf(
        "noir" to "black", "schwarz" to "black", "negro" to "black", "nero" to "black", "onyx" to "black",
        "blanc" to "white", "weiss" to "white", "blanco" to "white", "bianco" to "white", "crystal" to "white",
        "gris" to "grey", "grau" to "grey", "gray" to "grey", "grigio" to "grey", "tech" to "grey",
        "vert" to "green", "grün" to "green", "verde" to "green", "forest" to "green",
        "bleu" to "blue", "blau" to "blue", "azul" to "blue", "silber" to "silver", "argent" to "silver",
    )

    /**
     * Look up a colour by its reported name within a model. Matching is tolerant (case/spacing/finish
     * insensitive, then token-overlap, then a localised-alias pass) so backend names in any locale still
     * hit the palette. When nothing matches we DO NOT fall back to the model's first paint (that is what
     * mislabelled Onyx-Black 7GTs as "Mystic Lilac" - issue #8); instead we keep the car's real reported
     * name but paint it a neutral graphite, so the label stays truthful and the render is never confidently
     * wrong. A null/blank name (colour genuinely unknown) also yields the neutral placeholder.
     */
    fun colorFor(model: CarModel, colorName: String?): PaintColor {
        val raw = colorName?.trim()?.takeIf { it.isNotBlank() }
            ?: return PaintColor("", NEUTRAL, "-")
        val target = norm(raw)
        // 1) exact (normalised) match.
        model.colors.firstOrNull { norm(it.name) == target }?.let { return it }
        // 2) token overlap: any word of the reported name appears in a palette name, or vice-versa.
        val targetTokens = raw.lowercase().split(Regex("[\\s\\-_]+")).filter { it.length >= 3 }
        model.colors.firstOrNull { pc ->
            val palTokens = pc.name.lowercase().split(' ')
            targetTokens.any { t -> palTokens.any { it == t } } ||
                palTokens.any { pt -> target.contains(pt) && pt.length >= 4 }
        }?.let { return it }
        // 3) localised aliases -> map a foreign colour word to an English token, then match a palette name.
        val aliasHit = (targetTokens + target).firstNotNullOfOrNull { ALIASES[it] }
        if (aliasHit != null) {
            model.colors.firstOrNull { it.name.lowercase().contains(aliasHit) }?.let { return it }
        }
        // 4) no palette match - keep the real name, neutral tint (never a wrong vivid paint).
        return PaintColor(raw, NEUTRAL, "-")
    }
}
