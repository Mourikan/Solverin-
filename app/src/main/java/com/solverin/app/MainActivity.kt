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
        spStack = findViewById(R.id.spStack)
        grid = findViewById(R.id.grid)
        tvSummary = findViewById(R.id.tvSummary)
        tvHand = findViewById(R.id.tvHand)

        bind(spScenario, Scenario.entries.map { it.name })
        bind(spStake, Stake.entries.map { it.name })
        bind(spHero, Position.entries.map { "Hero ${it.short}" })
        bind(spVillain, Position.entries.map { "Vs ${it.short}" })
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
        val caller = if (scenario == Scenario.SQUEEZE)
            Position.entries.firstOrNull { it.ordinal > villain.ordinal && it.ordinal < hero.ordinal } ?: return null
        else null
        val key = SpotKey.of(scenario, hero, villain, caller)
        return key.takeIf { engine.isValid(it, ctx) }
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

    private fun showHand(h: Int) {
        val a = current ?: return
        val d = a.decision(h)
        val m = d.mix
        tvHand.text = String.format(
            Locale.US, "%s  R %.0f%%  C %.0f%%  F %.0f%%%s\nEV call %s  EV raise %+.2fbb  eq vs cont %.1f%%  FE %.1f%%",
            HandClass.name(h), m.raise * 100, m.call * 100, m.fold * 100,
            if (d.isValue && m.raise > 0f) "  [valor]" else if (m.raise > 0f) "  [farol]" else "",
            if (d.evCall.isNaN()) "-" else String.format(Locale.US, "%+.2fbb", d.evCall),
            d.evRaise, d.valueEquity * 100, d.foldEquity * 100,
        )
    }
}
