package com.solverin.app.presentation

import com.solverin.engine.core.Stake
import java.util.Locale

/** Hold-to-explain overlay content: a title and 3–4 tactical points built from the solved node's numbers. */
data class StrategyExplanation(val title: String, val points: List<String>)

/**
 * Deterministic Spanish explainer of hero's Max-EV decision. Every sentence is assembled from the solved node
 * ([OracleResult.options] EVs / Nash frequencies and [NodeMath]): EV verdict vs the runner-up, range / nut advantage and
 * realization, blockers / MDF / fold equity, and the rake of the selected stake.
 */
object SpanishStrategyExplainer {

    private val L: Locale = Locale.US

    fun explain(result: OracleResult, stake: Stake, profile: VillainProfilePreset, committedKey: String? = null): StrategyExplanation {
        val ranked = result.options.filter { it.evBb.isFinite() }.sortedByDescending { it.evBb }
        val best = ranked.firstOrNull() ?: return StrategyExplanation("Sin nodo resuelto", listOf("Introduce cartas y posición para resolver el nodo del héroe."))
        val m = result.math
        val title = "Por qué ${best.label.uppercase(L)} · ${result.nodeLabel}" + if (result.approximate) " (línea aproximada)" else ""
        val points = ArrayList<String>(4)
        points += verdict(best, ranked, result.options.firstOrNull { it.key == committedKey })
        if (m != null) {
            points += rangePoint(m, best)
            points += blockerPoint(m, best)
            points += rakePoint(m, stake, profile, best, ranked)
        }
        return StrategyExplanation(title, points)
    }

    private fun verdict(best: OracleOption, ranked: List<OracleOption>, committed: OracleOption?): String {
        val second = ranked.getOrNull(1)
        val sb = StringBuilder()
        sb.append("1. Veredicto EV: ${best.label} rinde ${bb(best.evBb)} (Nash ${pct(best.frequency.toDouble())}). ")
        if (second != null) {
            sb.append("Supera a ${second.label} (${bb(second.evBb)}) por ΔEV ${bb(best.evBb - second.evBb)}")
            sb.append(if (best.evBb - second.evBb < 0.1) ": decisión casi indiferente, el margen es mínimo." else ".")
        } else sb.append("Es la única acción legal con EV calculado.")
        if (committed != null && committed.key != best.key && committed.evBb.isFinite())
            sb.append(" Tu desvío (${committed.label}) cuesta ${bb(committed.evBb - best.evBb)}.")
        return sb.toString()
    }

    private fun rangePoint(m: NodeMath, best: OracleOption): String {
        val parts = ArrayList<String>()
        m.equity?.let { eq ->
            val r = m.realization
            if (r != null && m.street != TableStreet.RIVER) {
                val rName = if (m.street == TableStreet.PREFLOP) "R_real" else "R_post"
                parts += "EQ bruta ${pct(eq)} × $rName ${num(r, 2)} = EQ realizada ${pct(eq * r)}"
            } else parts += "EQ bruta ${pct(eq)}"
        }
        m.rangeAdvantage?.let { parts += "ΔRango ${signedPct(it)}" }
        m.nutAdvantage?.let {
            parts += "ΔNuts ${signedPct(it)} (" + when {
                it > 0.05 -> "ventaja de nuts: presión y tamaños grandes"
                it < -0.05 -> "el rival tiene más nuts: controla el bote"
                else -> "nuts parejas"
            } + ")"
        }
        parts += "SPR ${num(m.spr, 1)}" + when {
            m.spr < 2.0 -> " (compromiso de stack: el all-in es natural)"
            m.spr > 8.0 -> " (bote profundo: prima la realización)"
            else -> ""
        }
        m.geometricSize?.let { parts += "Sizing 2E geométrico ${pct(it)} del bote" }
        if (m.street != TableStreet.PREFLOP) {
            parts += if (m.aggressionCapped) "freno de vulnerabilidad / fourFlushShield / tope de faroles ACTIVO: se recorta la agresión de esta mano"
            else "freno de vulnerabilidad inactivo"
            m.overrideThresholdBb?.let { th ->
                parts += if (m.overrideActive && best.kind.aggressive) "PostflopMassiveExploitOverride ACTIVO: farol/semi-farol con margen ≥ ${num(th, 2)} bb, se ejecuta aunque supere el tope de faroles"
                else "override explotativo inactivo (umbral ${num(th, 2)} bb)"
            }
        }
        return "2. Rango y realización: " + parts.joinToString(" · ") + "."
    }

    private fun blockerPoint(m: NodeMath, best: OracleOption): String {
        val parts = ArrayList<String>()
        m.mdf?.let { parts += "MDF del rival ${pct(it)}" }
        m.pFoldVillain?.let { parts += "P(Fold_V) real ${pct(it)}" + if (m.mdf != null && it > 1 - m.mdf + 0.05) " (sobre-foldea frente al MDF: farol rentable)" else "" }
        m.blockerFactor?.let {
            parts += "BlockerFactor ${num(it, 2)} " + if (it < 1.0) "(tus cartas quitan ${pct(1 - it)} de su rango de continuación)" else "(sin efecto bloqueador relevante)"
        }
        m.bNut?.let { parts += "B_nut ${num(it, 2)}" + if (it < 1.0) " (bloqueas sus nuts)" else "" }
        m.bFold?.let { parts += "B_fold ${num(it, 2)}" + if (it < 1.0) " (bloqueas manos que foldean: peor farol)" else "" }
        m.potOdds?.let { po ->
            val need = "necesitas ${pct(po)} de equity para pagar"
            parts += m.equity?.let { eq -> need + if (eq * (m.realization ?: 1.0) >= po) " y la tienes" else " y no la alcanzas" } ?: need
        }
        if (m.bluffShare != null && m.alpha != null)
            parts += "faroles del rival ${pct(m.bluffShare)} vs α ${pct(m.alpha)}" + if (m.bluffShare < m.alpha) " (infra-farolea: fold explotativo permitido)" else ""
        if (parts.isEmpty()) parts += if (best.kind.aggressive) "sin fold equity modelada en este nodo" else "nodo pasivo: sin presión de fold"
        return "3. Bloqueadores y fold equity: " + parts.joinToString(" · ") + "."
    }

    private fun rakePoint(m: NodeMath, stake: Stake, profile: VillainProfilePreset, best: OracleOption, ranked: List<OracleOption>): String {
        val sb = StringBuilder("4. Rake ${stake.name}: ${pct(m.rakeRate)} con cap ${num(m.rakeCapBb, 1)} bb")
        val r = m.rakeBb
        if (m.street == TableStreet.PREFLOP) {
            sb.append(if (r != null && r > 0.0) "; rake imputado a la línea ${num(r, 2)} bb" else "; sin flop no hay rake (no flop, no drop)")
            when (m.coldCallVerdict) {
                "REJECT_RAKE" -> sb.append(". El cold call se descarta: el rake de ${stake.name} lo convierte en −EV")
                "REJECT_REALIZATION" -> sb.append(". El cold call se descarta por baja realización fuera de posición")
                "REJECT_DOMINATION" -> sb.append(". El cold call se descarta por dominación")
                "REJECT_SQUEEZE_RISK" -> sb.append(". El cold call se descarta por riesgo de squeeze")
                "REJECT_NEGATIVE_EV" -> sb.append(". El cold call es −EV")
                "PREFER_RAISE" -> sb.append(". Mejor 3-bet que flat: el raise evita pagar rake en botes multiway")
                else -> {}
            }
        } else if (r != null) sb.append("; rake esperado de la línea ${num(r, 2)} bb (bote proyectado hasta el showdown, limitado por el cap)")
        val call = ranked.firstOrNull { it.kind == ActKind.CALL }
        if (call != null && r != null && r > 0.0 && call.evBb < 0.0 && call.evBb + r > 0.0)
            sb.append(". Sin rake el CALL sería +EV (${bb(call.evBb + r)}): el rake decide el fold")
        sb.append(
            if (m.rakeCapBb >= 3.0) ". Microstakes: el cap alto castiga los botes pequeños y los calls marginales"
            else ". Cap bajo: los botes grandes pagan proporcionalmente menos rake",
        )
        if (profile != VillainProfilePreset.POPULATION) sb.append(". Node lock: ${profile.label}")
        if (best.kind == ActKind.FOLD) sb.append(". Foldear no paga rake")
        return sb.append('.').toString()
    }

    private val ActKind.aggressive: Boolean get() = this == ActKind.BET || this == ActKind.RAISE || this == ActKind.ALL_IN

    private fun bb(x: Double) = String.format(L, "%+.2f bb", x)
    private fun pct(x: Double) = String.format(L, "%.1f%%", x * 100)
    private fun signedPct(x: Double) = String.format(L, "%+.1f%%", x * 100)
    private fun num(x: Double, d: Int) = if (x.isInfinite()) "∞" else String.format(L, "%.${d}f", x)
}
