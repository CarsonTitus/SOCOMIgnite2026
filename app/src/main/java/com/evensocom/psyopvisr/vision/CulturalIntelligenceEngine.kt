package com.evensocom.psyopvisr.vision

/**
 * Cultural intelligence knowledge base and multi-signal inference engine.
 *
 * Replaces the simple label→text lookup of CulturalContextEngine with compound
 * reasoning that considers:
 *   - Detected religious/ideological symbols (from YoloV8Detector)
 *   - Scene classification (from MobileCLIP)
 *   - Sign text inventory (from OCR)
 *   - Detected generic objects (from EfficientDet / YoloV8)
 *
 * Outputs a [CulturalAssessment] containing faction identification, cultural
 * norms, behavioral protocols, and tactical implications.
 */
object CulturalIntelligenceEngine {

    // ── output type ─────────────────────────────────────────────────────────

    data class CulturalAssessment(
        val faction: String,           // identified cultural/ideological group
        val environment: String,       // inferred environment type
        val culturalNorms: List<String>,
        val behavioralProtocols: List<String>,
        val tacticalImplications: List<String>,
        val alertLevel: Int,           // 0=info  1=caution  2=critical
        val confidence: Float,
        val hudSummary: String,        // short line for G2 HUD
        val hudDetail: String          // longer context line
    )

    // ── symbol knowledge base ────────────────────────────────────────────────

    data class SymbolProfile(
        val canonicalName: String,
        val faction: String,
        val environmentHint: String,
        val norms: List<String>,
        val protocols: List<String>,
        val tacticalNotes: List<String>,
        val baseAlertLevel: Int
    )

    private val SYMBOL_PROFILES: Map<String, SymbolProfile> = mapOf(

        "buddhism" to SymbolProfile(
            canonicalName    = "Buddhist Symbol",
            faction          = "Buddhist community / temple",
            environmentHint  = "religious site",
            norms            = listOf(
                "Remove footwear before entering",
                "Clockwise circumambulation of shrines",
                "Silence or low speech near altars",
                "Do not point feet toward Buddha images",
                "Photography may be restricted"
            ),
            protocols        = listOf(
                "Approach with respectful demeanor",
                "Hands in anjali mudra (prayer position) as greeting",
                "Avoid physical contact with monks/nuns",
                "Accept monk interaction — monks are influential community figures"
            ),
            tacticalNotes    = listOf(
                "Buddhist monasteries serve as community hubs; monks hold social authority",
                "Religious sites frequently used as neutral meeting grounds",
                "Presence may indicate civilian population with Buddhist majority"
            ),
            baseAlertLevel   = 0
        ),

        "christianity" to SymbolProfile(
            canonicalName    = "Christian Cross",
            faction          = "Christian community / church",
            environmentHint  = "religious site",
            norms            = listOf(
                "Modest dress expected — cover shoulders/knees",
                "Remove hats in Orthodox and Catholic settings",
                "Silence during services",
                "Gender-separated seating possible in Orthodox churches"
            ),
            protocols        = listOf(
                "Greet with handshake — physical contact generally accepted",
                "Observe service times (Sunday primary; Friday for some denominations)",
                "Church officials (priests, pastors) are community leaders with influence"
            ),
            tacticalNotes    = listOf(
                "Church buildings often serve as aid distribution points in conflict zones",
                "Christian communities may have ties to international NGOs",
                "Cross on military insignia may indicate chaplaincy or faith-based unit"
            ),
            baseAlertLevel   = 0
        ),

        "cresent_and_star" to SymbolProfile(
            canonicalName    = "Crescent and Star",
            faction          = "Islamic community / mosque",
            environmentHint  = "religious site",
            norms            = listOf(
                "Remove footwear before entering mosque",
                "Women: cover hair with hijab; modest full-length clothing",
                "Men: cover from navel to knee minimum",
                "No pork products or alcohol visible",
                "Prayer occurs five times daily — Azan (call to prayer) precedes each",
                "Ramadan: no eating/drinking in public during daylight hours",
                "Friday Jumu'ah prayer (12–13:00 local) is obligatory — expect crowd"
            ),
            protocols        = listOf(
                "Greet with 'As-salamu alaykum' — acknowledge as sign of respect",
                "Right hand for greetings and offering items",
                "Request permission before entering female-only areas",
                "Imam is primary authority figure — engage through him for community matters"
            ),
            tacticalNotes    = listOf(
                "Mosque attendance peaks Friday midday — high civilian density",
                "Crescent on flags/insignia: Turkey, Pakistan, Algeria, Libya, many others",
                "Green crescent flag: Hamas or Islamist political context possible",
                "Combined with military insignia: Islamic state/military unit indicator",
                "Muezzin loudspeaker: indicates mosque proximity — community anchor point"
            ),
            baseAlertLevel   = 0
        ),

        "hinduism" to SymbolProfile(
            canonicalName    = "Hindu Symbol (Om/Trishul/Swastika)",
            faction          = "Hindu community / temple",
            environmentHint  = "religious site",
            norms            = listOf(
                "Remove footwear outside temple gate",
                "Wash hands and feet at entrance",
                "No leather goods inside some temples (cow is sacred)",
                "Women may need head covering",
                "Non-Hindus may be restricted from inner sanctum (garbhagriha)",
                "Pradakshina: clockwise circumambulation of deity"
            ),
            protocols        = listOf(
                "Namaste greeting (palms together) is appropriate",
                "Priest (pujari) leads rituals — consult for community access",
                "Avoid disrupting puja (worship) ceremonies",
                "Offer items with right hand"
            ),
            tacticalNotes    = listOf(
                "Hindu swastika (manji) rotates clockwise — distinct from Nazi variant",
                "Temple festivals (melas) draw large civilian crowds — high-density events",
                "Presence in South Asia, SE Asia, diaspora communities globally",
                "RSS shakhas (Hindu nationalist groups) may use Hindu symbols + saffron flags"
            ),
            baseAlertLevel   = 0
        ),

        "judaism" to SymbolProfile(
            canonicalName    = "Star of David / Menorah",
            faction          = "Jewish community / synagogue",
            environmentHint  = "religious site",
            norms            = listOf(
                "Men cover heads (kippah) in synagogue",
                "Shabbat restrictions: Friday sundown to Saturday nightfall — no work/vehicles",
                "Kosher dietary laws — separate meat/dairy",
                "Gender separation in Orthodox settings",
                "Torah is sacred — do not touch scroll without permission"
            ),
            protocols        = listOf(
                "Rabbi is primary authority — engage respectfully",
                "Handshake acceptable in non-Orthodox settings",
                "Avoid Shabbat operational scheduling if possible",
                "IDF insignia context: Star of David on military = Israeli Defense Forces"
            ),
            tacticalNotes    = listOf(
                "Star of David on military vehicle/uniform = IDF or Israeli security",
                "Star of David in civilian context = Jewish community center, synagogue, or cemetery",
                "Menorah: symbol of State of Israel (distinct from Nazi persecution context)",
                "Anti-Semitic graffiti combined with Star of David = hostile/extremist actor threat"
            ),
            baseAlertLevel   = 0
        ),

        "swastika" to SymbolProfile(
            canonicalName    = "Swastika",
            faction          = "Context-dependent: Buddhist/Hindu (religious) OR Neo-Nazi/extremist",
            environmentHint  = "ambiguous — requires disambiguation",
            norms            = listOf(
                "Hindu/Buddhist swastika: clockwise rotation, sacred symbol — alert 0",
                "Nazi Hakenkreuz: tilted 45°, counter-clockwise in NS context — alert 2",
                "Context determines threat level — look for co-present symbols"
            ),
            protocols        = listOf(
                "Identify co-present symbols before assessing threat",
                "Buddhist/Hindu temple setting → religious, no threat",
                "Urban graffiti / white supremacist paraphernalia → extremist indicator"
            ),
            tacticalNotes    = listOf(
                "Neo-Nazi / white supremacist: elevated threat — document and report",
                "Combined with SS runes, Iron Eagle, or 88 text: confirmed extremist indicator",
                "Hindu/Buddhist context: manji — no threat, religious significance only",
                "In MENA/South Asia context: Hindu diaspora religious use, not extremist"
            ),
            baseAlertLevel   = 1   // elevated until disambiguation — see compound rules
        )
    )

    // ── scene + object compound rules ───────────────────────────────────────

    data class CompoundRule(
        val id: String,
        val description: String,
        val evaluate: (symbols: Set<String>, scene: String, objects: Set<String>, signs: Set<String>) -> CompoundMatch?
    )

    data class CompoundMatch(
        val faction: String,
        val environment: String,
        val tacticalNotes: List<String>,
        val alertLevel: Int,
        val confidence: Float
    )

    private val COMPOUND_RULES: List<CompoundRule> = listOf(

        CompoundRule("swastika_religious_ctx",
            "Swastika in Buddhist/Hindu environment → religious, not extremist") { symbols, scene, objects, signs ->
            val hasSwastika = symbols.contains("swastika")
            val hasBuddhistHindu = symbols.any { it == "buddhism" || it == "hinduism" }
            val religiousScene = scene.contains("religious") || scene.contains("temple") || scene.contains("shrine")
            if (hasSwastika && (hasBuddhistHindu || religiousScene)) {
                CompoundMatch(
                    faction      = "Buddhist/Hindu religious space",
                    environment  = "Place of worship",
                    tacticalNotes = listOf(
                        "Swastika confirmed as Hindu/Buddhist manji — no extremist threat",
                        "Treat as standard religious site protocols"
                    ),
                    alertLevel   = 0,
                    confidence   = 0.85f
                )
            } else null
        },

        CompoundRule("swastika_extremist_ctx",
            "Swastika without Buddhist/Hindu context → extremist indicator") { symbols, scene, objects, signs ->
            val hasSwastika = symbols.contains("swastika")
            val hasBuddhistHindu = symbols.any { it == "buddhism" || it == "hinduism" }
            val urbanScene = scene.contains("urban") || scene.contains("street") ||
                             scene.contains("building") || scene == "unknown"
            val noBuddhistHinduObj = objects.none { it.contains("temple") || it.contains("shrine") }
            if (hasSwastika && !hasBuddhistHindu && (urbanScene || noBuddhistHinduObj)) {
                CompoundMatch(
                    faction      = "Potential Neo-Nazi / extremist actor",
                    environment  = "Extremist symbol — hostile indicator",
                    tacticalNotes = listOf(
                        "CRITICAL: Swastika in non-religious context indicates extremist presence",
                        "Document location. Check for co-present white supremacist symbology.",
                        "Do not engage without force protection"
                    ),
                    alertLevel   = 2,
                    confidence   = 0.80f
                )
            } else null
        },

        CompoundRule("islamic_military",
            "Crescent + military objects/uniforms → Islamic military unit") { symbols, scene, objects, signs ->
            val hasCrescent = symbols.contains("cresent_and_star")
            val hasMilitary = scene.contains("military") ||
                              objects.any { it.contains("uniform") || it.contains("weapon") || it.contains("truck") } ||
                              signs.any { it.contains("CHECKPOINT") || it.contains("RESTRICTED") }
            if (hasCrescent && hasMilitary) {
                CompoundMatch(
                    faction      = "Islamic military / paramilitary unit",
                    environment  = "Controlled military zone",
                    tacticalNotes = listOf(
                        "Islamic military forces present — confirm unit ID and ROE",
                        "Green flag variants: Hamas, Hezbollah, or other Islamist factions",
                        "Expect checkpoint procedures and identity verification",
                        "Prayer time interruptions possible — plan operations around Salah"
                    ),
                    alertLevel   = 1,
                    confidence   = 0.80f
                )
            } else null
        },

        CompoundRule("multi_faith",
            "Multiple distinct religious symbols → interfaith or contested space") { symbols, scene, objects, signs ->
            val faithCount = listOf("buddhism","christianity","cresent_and_star","hinduism","judaism")
                .count { it in symbols }
            if (faithCount >= 2) {
                CompoundMatch(
                    faction      = "Multi-faith / interfaith environment",
                    environment  = "Interfaith facility or contested religious space",
                    tacticalNotes = listOf(
                        "Multiple faiths present — high civilian sensitivity",
                        "Interfaith sites may be flashpoints for sectarian tension",
                        "Neutrality critical — do not favor any group visibly",
                        "NGO or UN presence likely in multi-faith humanitarian contexts"
                    ),
                    alertLevel   = 1,
                    confidence   = 0.75f
                )
            } else null
        },

        CompoundRule("cross_military",
            "Christian cross + military context → chaplaincy or Christian military unit") { symbols, scene, objects, signs ->
            val hasCross = symbols.contains("christianity")
            val hasMilitary = scene.contains("military") ||
                              objects.any { it.contains("uniform") || it.contains("weapon") }
            if (hasCross && hasMilitary) {
                CompoundMatch(
                    faction      = "Military chaplaincy or Christian-affiliated military unit",
                    environment  = "Military facility with religious support",
                    tacticalNotes = listOf(
                        "Chaplain presence indicates command concern for morale/welfare",
                        "Christian military units: Philippines AFP, US Army (chaplain corps), others",
                        "Memorial or fallen soldier context possible"
                    ),
                    alertLevel   = 0,
                    confidence   = 0.70f
                )
            } else null
        },

        CompoundRule("unattended_items_crowd",
            "Unattended bags + 3+ persons → IED risk protocol") { symbols, scene, objects, signs ->
            val hasUnattended = objects.any { it in setOf("backpack","suitcase","bag","bottle") }
            val personCount = objects.count { it == "person" }
            if (hasUnattended && personCount >= 3) {
                CompoundMatch(
                    faction      = "Crowd with unattended items",
                    environment  = "Potential IED risk area",
                    tacticalNotes = listOf(
                        "CRITICAL: Unattended bag(s) in crowd — IED risk protocol active",
                        "Establish 100m exclusion zone pending EOD assessment",
                        "Identify bag owner before approach. Do not touch."
                    ),
                    alertLevel   = 2,
                    confidence   = 0.85f
                )
            } else null
        }
    )

    // ── public entry point ───────────────────────────────────────────────────

    /**
     * Evaluates all detected signals and returns a [CulturalAssessment].
     *
     * @param detectedSymbols  Class names from YoloV8Detector (e.g. "buddhism", "cresent_and_star")
     * @param sceneCategory    Top MobileCLIP scene label (e.g. "military facility")
     * @param detectedObjects  Generic object labels from EfficientDet/COCO (e.g. "person", "backpack")
     * @param signTexts        OCR-extracted sign text (uppercased)
     * @param clipConfidence   MobileCLIP scene confidence
     */
    fun assess(
        detectedSymbols: Collection<String>,
        sceneCategory: String,
        detectedObjects: Collection<String>,
        signTexts: Collection<String>,
        clipConfidence: Float = 0.5f
    ): CulturalAssessment {

        val symbolSet  = detectedSymbols.map { it.lowercase().trim() }.toSet()
        val objectSet  = detectedObjects.map  { it.lowercase().trim() }.toSet()
        val signSet    = signTexts.map        { it.uppercase().trim() }.toSet()
        val sceneLow   = sceneCategory.lowercase()

        // 1. Try compound rules first (highest specificity)
        for (rule in COMPOUND_RULES) {
            val match = rule.evaluate(symbolSet, sceneLow, objectSet, signSet)
            if (match != null) {
                return buildAssessment(
                    faction          = match.faction,
                    environment      = match.environment,
                    norms            = symbolSet.firstNotNullOfOrNull { SYMBOL_PROFILES[it]?.norms } ?: emptyList(),
                    protocols        = symbolSet.firstNotNullOfOrNull { SYMBOL_PROFILES[it]?.protocols } ?: emptyList(),
                    tacticalNotes    = match.tacticalNotes,
                    alertLevel       = match.alertLevel,
                    confidence       = match.confidence,
                    symbolSet        = symbolSet,
                    sceneCategory    = sceneCategory
                )
            }
        }

        // 2. Single-symbol profile
        val primarySymbol = symbolSet.firstOrNull { SYMBOL_PROFILES.containsKey(it) }
        if (primarySymbol != null) {
            val profile = SYMBOL_PROFILES[primarySymbol]!!
            return buildAssessment(
                faction       = profile.faction,
                environment   = profile.environmentHint,
                norms         = profile.norms,
                protocols     = profile.protocols,
                tacticalNotes = profile.tacticalNotes,
                alertLevel    = profile.baseAlertLevel,
                confidence    = clipConfidence.coerceAtLeast(0.65f),
                symbolSet     = symbolSet,
                sceneCategory = sceneCategory
            )
        }

        // 3. Scene-only inference (no religious symbols detected)
        return sceneOnlyInference(sceneLow, signSet, objectSet, clipConfidence)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun buildAssessment(
        faction: String, environment: String,
        norms: List<String>, protocols: List<String>, tacticalNotes: List<String>,
        alertLevel: Int, confidence: Float,
        symbolSet: Set<String>, sceneCategory: String
    ): CulturalAssessment {
        val alertPrefix = when (alertLevel) {
            2    -> "CRITICAL"
            1    -> "CAUTION"
            else -> "INFO"
        }
        val primarySymbolLabel = symbolSet.firstOrNull()?.let {
            SYMBOL_PROFILES[it]?.canonicalName
        } ?: sceneCategory.uppercase()

        return CulturalAssessment(
            faction                = faction,
            environment            = environment,
            culturalNorms          = norms.take(4),
            behavioralProtocols    = protocols.take(3),
            tacticalImplications   = tacticalNotes.take(3),
            alertLevel             = alertLevel,
            confidence             = confidence,
            hudSummary             = "$alertPrefix: $primarySymbolLabel",
            hudDetail              = tacticalNotes.firstOrNull() ?: "Standard cultural protocols apply"
        )
    }

    private fun sceneOnlyInference(
        scene: String,
        signs: Set<String>,
        objects: Set<String>,
        conf: Float
    ): CulturalAssessment {
        val (faction, env, notes, alert) = when {
            scene.contains("military") ||
            signs.any { it.contains("RESTRICTED") || it.contains("CHECKPOINT") || it.contains("AUTHORIZED") } ->
                Quad("Military/government controlled zone", "Controlled access facility",
                    listOf("Expect identity verification", "Unauthorized access monitored", "ROE may differ"), 1)

            scene.contains("medical") ||
            signs.any { it.contains("HOSPITAL") || it.contains("CLINIC") || it.contains("MEDICAL") } ->
                Quad("Medical facility environment", "Medical area",
                    listOf("Protected under Geneva Conventions", "Priority access to emergency personnel",
                           "Do not obstruct medical operations"), 0)

            signs.any { it.contains("MINE") || it.contains("EXPLOSIVE") || it.contains("IED") || it.contains("BOMB") } ->
                Quad("Explosive hazard zone", "Denied area",
                    listOf("EXPLOSIVE HAZARD — do not approach without EOD clearance",
                           "Establish standoff distance minimum 100m"), 2)

            scene.contains("conflict") ||
            signs.any { it.contains("DANGER") || it.contains("ARMED") || it.contains("THREAT") } ->
                Quad("Active threat environment", "Conflict zone",
                    listOf("Heightened vigilance required", "Identify exits", "Seek cover"), 2)

            scene.contains("government") ||
            signs.any { it.contains("OFFICIAL") || it.contains("GOVERNMENT") } ->
                Quad("Government / administrative building", "Government facility",
                    listOf("Officials present — maintain professional demeanor",
                           "Photography likely restricted"), 1)

            scene.contains("market") || scene.contains("commercial") ->
                Quad("Commercial market environment", "Open market",
                    listOf("High civilian density", "Unattended bag protocol applies",
                           "Money-changing and vendor activity normal"), 0)

            else ->
                Quad("Unclassified environment", scene.ifBlank { "Unknown" },
                    listOf("No specific cultural/tactical indicators detected"), 0)
        }

        return CulturalAssessment(
            faction              = faction,
            environment          = env,
            culturalNorms        = emptyList(),
            behavioralProtocols  = emptyList(),
            tacticalImplications = notes,
            alertLevel           = alert,
            confidence           = conf,
            hudSummary           = "${if (alert >= 2) "CRITICAL" else if (alert == 1) "CAUTION" else "INFO"}: $env",
            hudDetail            = notes.firstOrNull() ?: ""
        )
    }

    // trivial 4-tuple for destructuring in when-branches
    private data class Quad<A,B,C,D>(val a:A, val b:B, val c:C, val d:D)
    private operator fun <A,B,C,D> Quad<A,B,C,D>.component1() = a
    private operator fun <A,B,C,D> Quad<A,B,C,D>.component2() = b
    private operator fun <A,B,C,D> Quad<A,B,C,D>.component3() = c
    private operator fun <A,B,C,D> Quad<A,B,C,D>.component4() = d
}
