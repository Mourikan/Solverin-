package com.solverin.app

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import com.solverin.engine.core.HandClass
import com.solverin.engine.core.Position
import com.solverin.engine.core.Stake
import com.solverin.engine.preflop.PreflopContext
import com.solverin.engine.preflop.SpotAnalysis
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val engine get() = (application as SolverinApp).engine
    private val worker = Executors.newSingleThreadExecutor()
    private val stacks = doubleArrayOf(40.0, 60.0, 100.0, 150.0, 200.0)

    private lateinit var spScenario: Spinner
    private lateinit var spStake: Spinner
    private lateinit var spHero: Spinner
    private lateinit var spVillain: Spinner
    private lateinit var spThird: Spinner
    private lateinit var spStack: Spinner
    private lateinit var grid: RangeGridView
    private lateinit var tvSummary: TextView
    private lateinit var tvHand: TextView

    private var current: SpotAnalysis? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        spScenario = findViewById(R.id.spScenario)
        spStake = findViewById(R.id.spStake)
        spHero = findViewById(R.id.spHero)
        spVillain = findViewById(R.id.spVillain)
        spThird = findViewById(R.id.spThird)
        spStack = findViewById(R.id.spStack)
        grid = findViewById(R.id.grid)
        tvSummary = findViewById(R.id.tvSummary)
        tvHand = findViewById(R.id.tvHand)

        bind(spScenario, Scenario.entries.map { it.name })
        bind(spStake, Stake.entries.map { it.name })
        bind(spHero, Position.entries.map { "Hero ${it.short}" })
        bind(spVillain, Position.entries.map { "Vs ${it.short}" })
        bind(spThird, listOf("3º: auto") + Position.entries.map { "3º: ${it.short}" })
        bind(spStack, stacks.map { "${it.toInt()}bb" })
        spStake.setSelection(Stake.NL50.ordinal)
        spHero.setSelection(Position.BTN.ordinal)
        spVillain.setSelection(Position.BB.ordinal)
        spStack.setSelection(2)

        grid.onHandSelected = { showHand(it) }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun bind(sp: Spinner, items: List<String>) {
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    private fun currentKey(ctx: PreflopContext): SpotKey? {
        val scenario = Scenario.of(spScenario.selectedItemPosition)
        val hero = Position.of(spHero.selectedItemPosition)
        val villain = Position.of(spVillain.selectedItemPosition)
        if (scenario == Scenario.RFI) {
            return if (hero == Position.BB) null else SpotKey.of(scenario, hero, Position.BB)
        }
        if (hero == villain) return null
        val picked = spThird.selectedItemPosition.let { if (it == 0) null else Position.of(it - 1) }
        val candidates: List<Position?> = when (scenario) {
            Scenario.SQUEEZE, Scenario.VS_SQUEEZE_OPENER, Scenario.VS_SQUEEZE_CALLER ->
                picked?.let { listOf(it) } ?: Position.entries
            Scenario.VS_4BET -> picked?.let { listOf(it) } ?: listOf(null)
            else -> listOf(null)
        }
        return candidates.asSequence()
            .filter { it != hero && it != villain }
            .map { SpotKey.of(scenario, hero, villain, it) }
            .firstOrNull { engine.isValid(it, ctx) }
    }

    private fun refresh() {
        if (!::spStack.isInitialized || spStack.adapter == null) return
        val ctx = PreflopContext(
            stake = Stake.entries[spStake.selectedItemPosition],
            effectiveStackBb = stacks[spStack.selectedItemPosition],
        )
        val key = currentKey(ctx)
        if (key == null) {
            current = null; grid.strategy = null
            tvSummary.text = "Spot no válido"
            return
        }
        worker.execute {
            val a = engine.analyze(key, ctx)
            runOnUiThread {
                current = a
                grid.strategy = a.strategy
                val s = a.strategy
                val sb = StringBuilder(String.format(
                    Locale.US, "%s | %s %.0fbb  raise %.1f%%  call %.1f%%  VPIP %.1f%%",
                    key, ctx.stake.name, ctx.effectiveStackBb,
                    s.raiseFrequency() * 100, s.callFrequency() * 100, s.vpip() * 100,
                ))
                sb.append(String.format(Locale.US, "\nSPR flop: call %s  raise %s", fmtSpr(a.sprCall), fmtSpr(a.sprRaise)))
                a.coldCall?.let {
                    sb.append(String.format(
                        Locale.US, "\n%s %s: %.1f%%  descartado por rake %.1f%%",
                        if (it.cold) "Cold call" else "Flat", if (it.inPosition) "IP" else "OOP",
                        it.frequency * 100, it.rakeRejected * 100,
                    ))
                }
                a.blindDefense?.let {
                    sb.append(String.format(
                        Locale.US, "\nDefensa: pot odds %.1f%%  eq req %.1f%% (realizada %.1f%%)  MDF %.1f%%  defend %.1f%%  3bet %.1f%%  %s",
                        it.potOdds * 100, it.requiredEquity * 100, it.requiredEquityRealized * 100,
                        it.mdf * 100, it.defend * 100, it.threeBet * 100, it.style,
                    ))
                }
                tvSummary.text = sb
            }
        }
    }

    private fun fmtSpr(v: Double) = if (v.isNaN()) "-" else String.format(Locale.US, "%.1f", v)

    private fun showHand(h: Int) {
        val a = current ?: return
        val d = a.decision(h)
        val m = d.mix
        val sb = StringBuilder(String.format(
            Locale.US, "%s  R %.0f%%  C %.0f%%  F %.0f%%%s",
            HandClass.name(h), m.raise * 100, m.call * 100, m.fold * 100,
            if (d.isValue && m.raise > 0f) "  [valor]" else if (m.raise > 0f) "  [farol]" else "",
        ))
        d.call?.let { t ->
            sb.append(String.format(
                Locale.US, "\nCALL  EV %+.2f = (1-%.2f)·[%.3f·%.2f·%.1f − %.2f − rake %.2f + impl %+.2f] − %.2f·%.2f",
                t.ev, t.pReraise, t.equity, t.realization, t.potTotal, t.investment, t.rake, t.implied,
                t.pReraise, t.investment,
            ))
        }
        d.call?.let { t ->
            sb.append(String.format(
                Locale.US, "\n  R = %.2f·%.2f·(1 %+.3f − %.3f)", t.basePos, t.initiative, t.playability, t.domination,
            ))
        }
        d.raise?.let { t ->
            sb.append(String.format(
                Locale.US, "\nRAISE EV %+.2f = %.2f·%.1f + %.2f·[%.3f·%.2f·%.1f − %.2f − rake %.2f + impl %+.2f] + %.2f·%+.2f",
                t.ev, t.pFold, t.foldWin, t.pCall, t.equity, t.realization, t.potTotal, t.investment, t.rake, t.implied,
                t.pReraise, t.evVsReraise,
            ))
            sb.append(String.format(
                Locale.US, "\n  P(fold) = 1 − MDF %.3f · BF %.3f · TM %.3f   R = %.2f·%.2f·(1 %+.3f − %.3f)",
                t.mdf, t.blockerFactor, t.tendency, t.basePos, t.initiative, t.playability, t.domination,
            ))
        }
        sb.append(String.format(Locale.US, "\neq vs continue %.1f%%  FE %.1f%%", d.valueEquity * 100, d.foldEquity * 100))
        d.coldCall?.let { sb.append("\nCold call: ").append(it.name) }
        tvHand.text = sb
    }
}
