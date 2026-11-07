package com.bydassistantng.quick

/**
 * Song PLUS edition: one phrase the offline recognizer heard but could not place, and what Gemini then did with it.
 *
 * [phrase] is kept the way the offline recognizer wrote it (normalised), not the way Gemini transcribed it: offline,
 * the only thing there is to match against is the offline recognizer's own output, and it writes a Tunisian phrase
 * its own way every time. [actions] are registry ids and the value words Gemini used; [scene] a scene id instead.
 */
data class LearnedCommand(
    val phrase: String,
    val actions: List<Pair<String, String>> = emptyList(),
    val scene: String? = null,
    val uses: Int = 0,
    val atMs: Long = 0L,
)

/**
 * Song PLUS edition: the car learns the driver's own words from Gemini, so they work offline next time.
 *
 * During an online conversation the first phrase is also recognised on the car (see [QuickCommands.beginTap]). When the
 * phrase book in [QuickParser] does not know it but Gemini then runs a car command, the pair is kept here. The next time
 * the offline recognizer writes the same (or nearly the same) thing, the command runs straight away, with or without
 * the internet. Pure logic, no Android: matched, learned and stored as text, all unit-tested.
 */
object LearnedCommands {
    const val MAX_ENTRIES = 200
    const val MAX_ACTIONS = 8
    const val MIN_PHRASE_CHARS = 3

    /** How alike two phrases must be (1.0 = identical) to count as the same command. High on purpose: a wrong match runs
     * a car command, so a near miss is left to the phrase book or Gemini instead. */
    const val MATCH_THRESHOLD = 0.85

    /** Commands never learned, whatever Gemini did: anything that locks or unlocks the car stays a deliberate, spoken ask. */
    fun learnable(commandId: String): Boolean =
        !commandId.contains("door", ignoreCase = true) && !commandId.contains("lock", ignoreCase = true)

    /** The best entry for [heard], or null when none is alike enough. */
    fun match(heard: String, entries: List<LearnedCommand>): LearnedCommand? {
        val t = QuickParser.normalize(heard)
        if (t.length < MIN_PHRASE_CHARS) return null
        var best: LearnedCommand? = null
        var bestScore = 0.0
        for (e in entries) {
            val s = similarity(t, e.phrase)
            if (s > bestScore) {
                best = e
                bestScore = s
            }
        }
        return best?.takeIf { bestScore >= MATCH_THRESHOLD }
    }

    /** 1.0 for the same phrase, 0.99 for the same words in another order, else one minus the share of letters that differ. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a.split(' ').toSet() == b.split(' ').toSet()) return 0.99
        return 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
    }

    fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /**
     * [entries] with what Gemini did for [phrase] added. [replace] starts the entry afresh (the first command of a new
     * conversation for this phrase — what the phrase meant last time no longer counts); otherwise the command is added to
     * what was learned a moment ago from the same phrase ("close everything" may be several calls). A scene replaces the
     * single commands. Newest first, at most [MAX_ENTRIES].
     */
    fun learn(
        entries: List<LearnedCommand>,
        phrase: String,
        action: Pair<String, String>?,
        scene: String?,
        nowMs: Long,
        replace: Boolean,
    ): List<LearnedCommand> {
        val p = QuickParser.normalize(phrase)
        if (p.length < MIN_PHRASE_CHARS) return entries
        val act = action?.takeIf { learnable(it.first) && storable(it.first) && storable(it.second) }
        val sc = scene?.takeIf { storable(it) }
        if (act == null && sc == null) return entries
        val existing = entries.firstOrNull { it.phrase == p }?.takeIf { !replace }
        val updated = when {
            sc != null -> LearnedCommand(p, emptyList(), sc, existing?.uses ?: 0, nowMs)
            existing == null -> LearnedCommand(p, listOf(act!!), null, 0, nowMs)
            // A scene already covers it: a single command after it adds nothing.
            existing.scene != null -> existing.copy(atMs = nowMs)
            else -> existing.copy(actions = (existing.actions.filter { it.first != act!!.first } + act!!).takeLast(MAX_ACTIONS), atMs = nowMs)
        }
        return (listOf(updated) + entries.filter { it.phrase != p }).sortedByDescending { it.atMs }.take(MAX_ENTRIES)
    }

    /** [entries] with one more use counted for [phrase]. */
    fun markUsed(entries: List<LearnedCommand>, phrase: String): List<LearnedCommand> =
        entries.map { if (it.phrase == phrase) it.copy(uses = it.uses + 1) else it }

    /** What to run for [entry]: its scene, or its commands; null when the scene no longer exists. */
    fun plan(entry: LearnedCommand): QuickPlan? = when {
        entry.scene != null -> Scenes.byId(entry.scene)?.let { QuickPlan(emptyList(), it) }
        entry.actions.isNotEmpty() -> QuickPlan(entry.actions)
        else -> null
    }

    // ── Storage: one entry per line, so a damaged line costs that line only ───────────────────────────────

    private const val FIELD = '\t'
    private const val ITEM = '|'
    private const val PAIR = '='

    private fun storable(s: String): Boolean = s.isNotBlank() && s.none { it == FIELD || it == ITEM || it == PAIR || it == '\n' || it == '\r' }

    fun encode(entries: List<LearnedCommand>): String = entries.joinToString("\n") { e ->
        listOf(
            e.phrase,
            e.scene.orEmpty(),
            e.actions.joinToString(ITEM.toString()) { (id, v) -> "$id$PAIR$v" },
            e.uses.toString(),
            e.atMs.toString(),
        ).joinToString(FIELD.toString())
    }

    fun decode(text: String?): List<LearnedCommand> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val f = line.split(FIELD)
            if (f.size != 5) return@mapNotNull null
            val phrase = f[0].takeIf { it.length >= MIN_PHRASE_CHARS } ?: return@mapNotNull null
            val actions = f[2].split(ITEM).filter { it.isNotEmpty() }.mapNotNull { item ->
                val kv = item.split(PAIR)
                if (kv.size == 2 && kv[0].isNotBlank() && kv[1].isNotBlank()) kv[0] to kv[1] else null
            }
            val scene = f[1].ifBlank { null }
            if (scene == null && actions.isEmpty()) return@mapNotNull null
            LearnedCommand(phrase, actions, scene, f[3].toIntOrNull() ?: 0, f[4].toLongOrNull() ?: 0L)
        }.take(MAX_ENTRIES).toList()
    }
}
