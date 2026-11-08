package com.bydassistantng.quick

import java.text.Normalizer

/** What an offline phrase asks for: single commands (registry id, value word), or one scene. */
data class QuickPlan(val actions: List<Pair<String, String>>, val scene: Scene? = null) {
    val allActions: List<Pair<String, String>> get() = scene?.actions ?: actions
}

/**
 * Song PLUS edition: turns a short spoken car command into registry commands without any network, in English,
 * French and Tunisian Arabic (Derja, in Arabic script as the offline recognizer writes it, mixed with French words).
 * Deliberately narrow: anything it is not sure of returns null and is left to Gemini.
 */
object QuickParser {

    fun parse(raw: String): QuickPlan? {
        val t = normalize(raw)
        if (t.isBlank()) return null
        val words = t.split(' ')

        sceneFor(t)?.let { return QuickPlan(emptyList(), it) }

        val number = numberIn(words)
        val on = has(t, *ON)
        val off = has(t, *OFF)

        // Ambient light before the cabin light and the A/C: "lumière d'ambiance bleue" is not the cabin light.
        if (has(t, *AMBIENT)) {
            colorIn(t)?.let { return single("ambient.color", it) }
            if (has(t, "dynamic", "dynamique", "متحرك", "ديناميك")) return single("ambient.dynamic", if (off) "off" else "on")
            if (number != null) return single("ambient.brightness", number.coerceIn(1, 10).toString())
            return null
        }

        if (has(t, *DEFROST)) {
            val rear = has(t, *REAR)
            return single(if (rear) "ac.rearDefrost" else "ac.frontDefrost", if (off) "off" else "on")
        }

        if (has(t, *SEAT)) {
            val kind = when {
                has(t, *HEAT) -> "heating"
                has(t, *COOL) -> "ventilation"
                else -> return null
            }
            val level = when {
                off -> "off"
                has(t, *LOW) -> "low"
                else -> "high"
            }
            val seats = when {
                has(t, *BOTH) -> listOf("driver", "passenger")
                has(t, *PASSENGER) -> listOf("passenger")
                else -> listOf("driver")
            }
            return QuickPlan(seats.map { "seat.$it.$kind" to level })
        }

        if (has(t, *STEERING)) return single("steeringWheel.heating", if (off) "off" else "on")

        if (has(t, *WINDOW)) {
            val action = windowAction(t) ?: return null
            val which = when {
                has(t, *ALL) -> ALL_WINDOWS
                has(t, *REAR) && has(t, *LEFT) -> listOf("window.rearLeft")
                has(t, *REAR) && has(t, *RIGHT) -> listOf("window.rearRight")
                has(t, *REAR) -> listOf("window.rearLeft", "window.rearRight")
                has(t, *PASSENGER) -> listOf("window.passenger")
                has(t, *DRIVER) -> listOf("window.driver")
                // "close the windows" means all of them; "open the window" only the driver's.
                action == "close" -> ALL_WINDOWS
                else -> listOf("window.driver")
            }
            return QuickPlan(which.map { it to action })
        }

        if (has(t, *SUNSHADE)) {
            return when {
                has(t, *OPEN) -> single("sunshade.percent", "100")
                has(t, *CLOSE) -> single("sunshade.percent", "0")
                else -> null
            }
        }
        if (has(t, *SUNROOF)) {
            return when {
                // The vent position is a different control on this car: the percentage route cannot express a tilt.
                has(t, *VENT) -> single("sunroof.tilt", "tilt")
                has(t, *OPEN) -> single("sunroof", "open")
                has(t, *CLOSE) -> single("sunroof", "close")
                else -> null
            }
        }

        if (has(t, *TRUNK)) {
            return when {
                has(t, *OPEN) -> single("trunk", "open")
                has(t, *CLOSE) -> single("trunk", "close")
                else -> null
            }
        }

        if (has(t, *RECIRC_IN)) return single("ac.airSource", "inside")
        if (has(t, *RECIRC_OUT)) return single("ac.airSource", "outside")

        if (has(t, *FAN) && number != null) return single("ac.fan", number.coerceIn(0, 7).toString())

        val ac = has(t, *AC)
        if ((ac || has(t, *TEMPERATURE)) && number != null && number in 16..33) {
            return single("ac.temperature", number.coerceIn(17, 33).toString())
        }
        if (ac) {
            return when {
                off -> single("ac.power", "off")
                on || has(t, *OPEN) -> single("ac.power", "on")
                else -> null
            }
        }

        if (has(t, *CABIN_LIGHT)) {
            return when {
                off || has(t, *CLOSE) -> single("cabin.light", "off")
                on || has(t, *OPEN) -> single("cabin.light", "on")
                else -> null
            }
        }

        if (has(t, *VOLUME) && number != null) return single("audio.volume", number.coerceIn(0, 39).toString())
        if (has(t, "unmute", "remets le son", "رجع الصوت", "هات الصوت", "رجعلي الصوت")) return single("audio.mediaMute", "unmute")
        if (has(t, "mute", "coupe le son", "silence", "سكت", "اسكت", "قص الصوت", "نحي الصوت", "سكتلي")) return single("audio.mediaMute", "mute")

        return null
    }

    private fun single(id: String, value: String) = QuickPlan(listOf(id to value))

    private fun windowAction(t: String): String? = when {
        has(t, *HALF) -> "half"
        has(t, *VENT) -> "breath"
        has(t, *STOP) -> "stop"
        has(t, *CLOSE) -> "close"
        has(t, *OPEN) -> "open"
        else -> null
    }

    private fun sceneFor(t: String): Scene? {
        val id = when {
            // Goodbye before close-all: "goodbye" tidies the whole cabin, of which closing the glass is one part.
            has(t, *SCENE_GOODBYE) -> "goodbye"
            has(t, *SCENE_CLOSE_ALL) -> "close_all"
            has(t, *SCENE_OFF) -> "climate_off"
            has(t, *SCENE_HOT) -> "cool_down"
            has(t, *SCENE_COLD) -> "warm_up"
            has(t, *SCENE_AIR) -> "air_out"
            else -> return null
        }
        return Scenes.byId(id)
    }

    /**
     * Whether [raw] just asks to do the last thing again ("again", "encore", "عاود"). The previous command is kept by
     * [QuickCommands], so this only has to recognise the ask; an empty or unmatched phrase is not a repeat.
     */
    fun isRepeat(raw: String): Boolean {
        val t = normalize(raw)
        return t.isNotBlank() && has(t, *REPEAT)
    }

    private fun colorIn(t: String): String? = COLORS.firstOrNull { (_, words) -> has(t, *words) }?.first

    // ── Text ─────────────────────────────────────────────────────────────────────────────────────

    /** Lower case, no accents or Arabic diacritics, one Arabic spelling per letter, punctuation as spaces. */
    fun normalize(s: String): String {
        var x = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        x = x.replace('ة', 'ه').replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي').replace('ٱ', 'ا')
            .replace("ـ", "") // tatweel
        x = x.replace(Regex("[^\\p{L}\\p{Nd}%]+"), " ").trim().replace(Regex(" +"), " ")
        return x
    }

    /**
     * Whether [t] contains any of [keys]. Latin keys match at the start of a word ("window" also matches "windows");
     * Arabic keys anywhere, since the article and prefixes are written onto the word ("والشباك"). Multi-word keys
     * match as a phrase.
     */
    fun has(t: String, vararg keys: String): Boolean = keys.any { k -> patternFor(k).containsMatchIn(t) }

    private val patterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun patternFor(k: String): Regex = patterns.getOrPut(k) {
        val key = normalize(k)
        when {
            key.isEmpty() -> Regex("(?!)")
            // Arabic: a whole word, which may carry the article and a one-letter prefix (و ف ب ل) in front and a
            // pronoun ending behind ("والشباك", "كرسيه"), but is not the start of a longer word ("الكل" ≠ "الكليم").
            key.any { it in '؀'..'ۿ' } -> {
                val bare = key.removePrefix("ال")
                Regex("(^| )(و|ف|ب|ل)?(ال)?" + Regex.escape(bare) + "(ه|ها|ي|هم|ك)?( |$)")
            }
            // Short Latin words must be whole words ("on" is not "one", "son" is not "song").
            key.length < 4 -> Regex("(^| )" + Regex.escape(key) + "( |$)")
            // Longer ones may carry an ending ("window" also matches "windows", "chauff" "chauffage").
            else -> Regex("(^| )" + Regex.escape(key))
        }
    }

    // ── Numbers ──────────────────────────────────────────────────────────────────────────────────

    /** The first number said, as digits or words in English, French or Arabic/Derja; null when there is none. */
    fun numberIn(words: List<String>): Int? {
        words.firstNotNullOfOrNull { w -> Regex("^\\d{1,3}").find(w)?.value?.toIntOrNull() }?.let { return it }
        var total: Int? = null
        var last: Int? = null
        for (raw in words) {
            if (total != null && raw in CONNECTORS) continue
            val w = if (raw.length > 2 && raw.startsWith("و") && NUMBER_WORDS.containsKey(normalize(raw.drop(1)))) raw.drop(1) else raw
            val v = NUMBER_WORDS[w]
            if (v == null) {
                if (total != null) break
                continue
            }
            total = when {
                v == 100 && total != null && total < 10 -> total * 100
                v == 20 && last == 4 && total == 4 -> 80 // "quatre-vingt"
                else -> (total ?: 0) + v
            }
            last = v
        }
        return total
    }

    private val CONNECTORS = setOf("et", "and", "و")

    private val NUMBER_WORDS: Map<String, Int> = buildMap {
        fun put(v: Int, vararg ws: String) = ws.forEach { put(normalize(it), v) }
        put(0, "zero", "zéro", "صفر")
        put(1, "one", "un", "une", "واحد", "وحده")
        put(2, "two", "deux", "اثنين", "اثنان", "ثنين", "زوز", "زوج")
        put(3, "three", "trois", "ثلاثه", "ثلاث", "تلاثه", "تلاث", "ثلاثة")
        put(4, "four", "quatre", "اربعه", "اربع", "أربعة")
        put(5, "five", "cinq", "خمسه", "خمس")
        put(6, "six", "سته", "ست", "ستة")
        put(7, "seven", "sept", "سبعه", "سبع")
        put(8, "eight", "huit", "ثمانيه", "ثمنيه", "تمنيه", "ثمانه", "ثمان")
        put(9, "nine", "neuf", "تسعه", "تسع")
        put(10, "ten", "dix", "عشره", "عشر")
        put(11, "eleven", "onze", "احداش", "حداش", "احدعش")
        put(12, "twelve", "douze", "اثناش", "ثناش", "طناش", "اثنعش")
        put(13, "thirteen", "treize", "ثلطاش", "تلطاش", "ثلاثطاش", "ثلتاش")
        put(14, "fourteen", "quatorze", "اربعطاش", "ربعطاش")
        put(15, "fifteen", "quinze", "خمسطاش", "خمستاش")
        put(16, "sixteen", "seize", "سطاش", "ستاش", "ستطاش")
        put(17, "seventeen", "سبعطاش", "سبعتاش")
        put(18, "eighteen", "ثمنطاش", "ثمانطاش", "تمنطاش")
        put(19, "nineteen", "تسعطاش", "تسعتاش")
        put(20, "twenty", "vingt", "عشرين")
        put(30, "thirty", "trente", "ثلاثين", "تلاثين", "ثلثين")
        put(40, "forty", "quarante", "اربعين")
        put(50, "fifty", "cinquante", "خمسين")
        put(60, "sixty", "soixante", "ستين")
        put(70, "seventy", "سبعين")
        put(80, "eighty", "ثمانين", "ثمنين")
        put(90, "ninety", "تسعين")
        put(100, "hundred", "cent", "ميه", "مية", "مائه")
    }

    // ── Vocabulary (English, French, Tunisian Derja in Arabic script) ────────────────────────────────

    private val ON = arrayOf("on", "turn on", "switch on", "start", "allume", "active", "mets", "demarre", "شعل", "ولع", "شغل", "حل", "شعللي", "شعلي", "ولعلي", "شغللي", "دور", "دورلي", "خدم", "خدملي", "اخدم", "حط")
    private val OFF = arrayOf("off", "turn off", "switch off", "eteins", "eteint", "coupe", "desactive", "arrete", "طفي", "اطفي", "سكر", "وقف", "طفيلي", "طفيها", "بطل", "بطللي", "قص", "قصلي", "نحي", "نحيلي", "سكرلي")
    private val OPEN = arrayOf("open", "lower", "down", "ouvre", "ouvrir", "ouvert", "baisse", "حل", "افتح", "فتح", "هبط", "حللي", "حلها", "افتحلي", "هبطلي", "نزل", "نزللي", "طيح", "طيحلي")
    private val CLOSE = arrayOf("close", "shut", "raise", "up", "ferme", "fermer", "monte", "سكر", "اغلق", "سد", "طلع", "سكرلي", "سكرها", "بلع", "بلعلي", "طلعلي", "ارفع", "هز", "هزلي")
    private val HALF = arrayOf("half", "moitie", "a moitie", "نص", "النص", "نص نص", "للنص")
    private val VENT = arrayOf("vent", "crack", "a little", "a bit", "entrouvre", "un peu", "aere", "شويه", "شوي")
    private val STOP = arrayOf("stop", "stoppe", "arrete", "وقف")

    private val WINDOW = arrayOf("window", "fenetre", "vitre", "glace", "شباك", "شبابك", "بلار", "فنتر", "فونتر", "الفنيتر", "البلاره", "بلارات", "فيتر", "الفيتره", "فيترينه", "الشبابك", "الطاقه")
    private val ALL = arrayOf("all", "every", "toutes", "tous", "الكل", "كلهم", "الاربعه", "الكلهم", "كلهم", "الكلهم", "الكل", "الاربعه", "جميع")
    private val DRIVER = arrayOf("driver", "my", "conducteur", "chauffeur", "متاعي", "السايق", "الشوفور", "الشيفور", "شيفور", "متاع الشيفور", "قدامي", "من جيهتي")
    private val PASSENGER = arrayOf("passenger", "passager", "بحذايا", "الراكب", "الاخر", "متاع البحذا", "الي بحذايا", "اللي بحذايا", "الجنب", "الكوبيلوت", "متاع الراكب", "قدام اليمين")
    private val BOTH = arrayOf("both", "les deux", "الزوز", "زوز")
    private val REAR = arrayOf("rear", "back", "arriere", "lunette", "اللور", "الوراني", "التالي", "الخلف", "اللاتنين", "ورا", "اللوراني", "التاليين", "التالين", "الكراسي التاليه", "من تالي")
    private val LEFT = arrayOf("left", "gauche", "يسار", "اليسار", "الشمال", "ليسار", "اليسرى", "يسرى", "الشمالي")
    private val RIGHT = arrayOf("right", "droite", "يمين", "اليمين", "ليمين", "اليمنى", "يمنى", "اليميني")

    private val AC = arrayOf(
        "ac", "a c", "air conditioning", "air conditioner", "aircon", "climate", "clim", "climatisation", "climatiseur",
        "كليم", "كليماتيزور", "كليماتيزاسيون", "مكيف", "المكيف", "التكييف", "الكوندسيون",
        "الكليم", "كليماتيزار", "الكوندي", "البرد", "الكلاماتيزور", "ليكليم",
    )
    private val TEMPERATURE = arrayOf("temperature", "degree", "degre", "درجه", "درجات", "تمبيراتور", "الحراره", "حراره", "درجه حراره")
    private val FAN = arrayOf("fan", "blower", "ventilateur", "vitesse", "souffle", "الفان", "مروحه", "المروحه", "فونتيلاتور", "الريح", "ريح", "الفنتيلاتور", "فنتيلاتور", "السرعه متاع الهوا", "قوه الهوا")
    private val DEFROST = arrayOf("defrost", "demist", "defog", "desembuage", "desembue", "degivrage", "degivre", "ضباب", "البخار", "الندى", "ديفروست", "ضبابه", "البخاريه", "الشبورة")
    private val RECIRC_IN = arrayOf("recirculation", "recycl", "air interieur", "inside air", "هوا داخل", "الهوا الداخلي", "هوا من داخل", "الريسيركيلاسيون", "سكر الهوا", "دور الهوا")
    private val RECIRC_OUT = arrayOf("outside air", "fresh air", "air exterieur", "air frais", "هوا برا", "الهوا الخارجي", "هوا من برا", "هوا من الخارج", "حل الهوا")

    private val SEAT = arrayOf("seat", "siege", "fauteuil", "كرسي", "الكرسي", "كراسي", "السياج", "فوتاي", "الفوتاي", "الكرسي متاعي")
    private val HEAT = arrayOf("heat", "warm", "chauff", "rechauff", "سخن", "تسخين", "سخان", "دفي", "شوفاج", "سخون", "سخني", "الشوفاج", "دفيني", "حمي")
    private val COOL = arrayOf("ventilat", "ventile", "cool", "refroid", "frais", "تهويه", "برد", "فانتيلاسيون", "هوي", "فنتيلاسيون", "برد الكرسي", "هويلي")
    private val LOW = arrayOf("low", "faible", "bas", "doux", "minimum", "خفيف", "شويه", "صغير", "شويه برك", "على خفيف", "لوطا")
    private val STEERING = arrayOf("steering wheel", "steering", "volant", "فولون", "الفولون", "المقود", "الستيرينق", "الفولان", "فولان", "الستيرنغ", "الدركسيون")

    private val SUNROOF = arrayOf("sunroof", "roof", "toit", "سقف", "السقف")
    private val SUNSHADE = arrayOf("sunshade", "shade", "store", "rideau", "ستار", "الستاره", "الرادو")
    private val TRUNK = arrayOf("trunk", "boot", "tailgate", "coffre", "hayon", "كوفر", "الكوفر", "صندوق", "الصندوق", "الماله", "الكوفر متاع تالي")
    private val CABIN_LIGHT = arrayOf("cabin light", "interior light", "reading light", "light", "plafonnier", "lumiere", "الضو", "ضو", "الضوء", "لومير", "الضوء", "الضو متاع داخل", "لامبه", "اللامبه")
    private val AMBIENT = arrayOf("ambient", "ambiance", "atmosphere", "mood light", "led", "الامبيانس", "امبيانس", "الاضاءه", "الضو الملون")
    private val VOLUME = arrayOf("volume", "son", "الصوت", "صوت", "فوليم", "الصوت على", "الموسيقى", "السون")

    private val SCENE_HOT = arrayOf(
        "i m hot", "im hot", "i am hot", "too hot", "it s hot", "so hot", "cool down", "cool the car", "j ai chaud", "il fait chaud",
        "rafraichi", "refroidi la voiture", "سخانه", "حامي", "نحس بالسخانه", "برد الكرهبه",
        "نموت بالسخانه", "سخون برشا", "الدنيا سخونه", "بردني", "برد عليا", "سخانه برشا",
    )
    private val SCENE_COLD = arrayOf(
        "i m cold", "im cold", "i am cold", "too cold", "it s cold", "warm up", "warm the car", "j ai froid", "il fait froid",
        "rechauffe la voiture", "بردان", "نحس بالبرد", "فما برد", "سخن الكرهبه",
        "البرد برشا", "نرعش", "سخني الكرهبه", "دفيني الكرهبه", "قارص",
    )
    private val SCENE_CLOSE_ALL = arrayOf(
        "close everything", "close all", "shut everything", "ferme tout", "tout fermer", "ferme toutes",
        "سكر كل شي", "سكر كلشي", "سكر الكل", "سكر كل حاجه",
        "سكر كل شيء", "سكر كلش", "بلع كلشي", "سكر كل شي من فضلك", "سكرلي كلشي",
    )
    private val SCENE_AIR = arrayOf("air out", "air the car", "aere la voiture", "aerer la voiture", "هوي الكرهبه", "خلي الهوا يدخل", "هويلي الكرهبه", "حل الهوا للكرهبه")
    private val SCENE_OFF = arrayOf("everything off", "turn everything off", "eteins tout", "coupe tout", "طفي كل شي", "طفي كلشي", "طفي الكل", "طفي كل شيء", "طفيلي كلشي", "بطل كلشي")

    // Leaving the car: tidy the cabin and close the glass. Deliberately not "salut" (also a greeting) or a bare "bye"
    // word that a longer sentence could trip; the phrases here are ones a driver says on the way out.
    private val SCENE_GOODBYE = arrayOf(
        "goodbye", "good bye", "bye bye", "i m leaving", "im leaving", "i am leaving", "i m off", "see you",
        // Not "a plus": "mets la clim a plus fort" would close the glass.
        "au revoir", "a bientot", "je pars", "je m en vais",
        "بسلامه", "في امان الله", "باي باي", "راني ماشي", "تصبح على خير",
        "مع السلامه", "بالسلامه", "نشوفك", "ثمه نمشي", "يزي نمشي",
    )

    // "Do that again": repeat the last offline command. Kept narrow so it never swallows a real command.
    // Not "زيد" ("زيد الصوت" is volume up) nor "remets" ("remets la clim"): they start real commands.
    private val REPEAT = arrayOf(
        "again", "do it again", "one more time", "repeat", "encore", "refais", "la meme chose",
        "عاود", "عاوده", "مره اخرى", "نفس الحاجه", "كيف قبل",
        "عاودها", "عاودلي", "مره ثانيه", "زادا مره", "نفس الشي",
    )

    private val ALL_WINDOWS = listOf("window.driver", "window.passenger", "window.rearLeft", "window.rearRight")

    /** Palette names (as in vehicle_commands.json) with what people say; two-word names first. */
    private val COLORS: List<Pair<String, Array<String>>> = listOf(
        "dark_blue" to arrayOf("dark blue", "navy", "bleu fonce", "bleu marine", "ازرق غامق", "كحلي"),
        "sky_blue" to arrayOf("sky blue", "light blue", "bleu ciel", "bleu clair", "سماوي", "ازرق فاتح"),
        "light_green" to arrayOf("light green", "vert clair", "اخضر فاتح"),
        "deep_orange" to arrayOf("dark orange", "deep orange", "orange fonce", "برتقالي غامق"),
        "warm_white" to arrayOf("warm white", "blanc chaud", "ابيض دافي", "ابيض سخون"),
        "cold_white" to arrayOf("cold white", "cool white", "blanc froid", "white", "blanc", "ابيض", "بيضا"),
        "red" to arrayOf("red", "rouge", "احمر", "حمرا", "روج"),
        "blue" to arrayOf("blue", "bleu", "ازرق", "زرقا", "بلو"),
        "cyan" to arrayOf("cyan", "turquoise", "فيروزي", "تركواز"),
        "mint" to arrayOf("mint", "menthe", "نعناعي"),
        "green" to arrayOf("green", "vert", "اخضر", "خضرا", "فار"),
        "lime" to arrayOf("lime", "citron vert"),
        "yellow" to arrayOf("yellow", "jaune", "اصفر", "صفرا", "جون"),
        "gold" to arrayOf("gold", "golden", "dore", "ذهبي"),
        "amber" to arrayOf("amber", "ambre", "عنبري"),
        "orange" to arrayOf("orange", "برتقالي", "اورانج", "ورانج"),
        "pink" to arrayOf("pink", "rose", "وردي", "روز"),
        "purple" to arrayOf("purple", "violet", "mauve", "بنفسجي", "موف", "فيولي"),
        "crimson" to arrayOf("crimson", "cramoisi", "bordeaux", "بوردو"),
        "coral" to arrayOf("coral", "corail", "مرجاني"),
    )
}
