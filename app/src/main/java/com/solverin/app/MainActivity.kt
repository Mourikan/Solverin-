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
import com.solverin.engine.range.Scenario
import com.solverin.engine.range.SpotKey
import com.solverin.engine.range.SpotStrategy
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

    private var current: SpotStrategy? = null

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

    private fun currentKey(): SpotKey? {
        val scenario = Scenario.of(spScenario.selectedItemPosition)
        val hero = Position.of(spHero.selectedItemPosition)
        val villain = Position.of(spVillain.selectedItemPosition)
        if (hero == villain) return null
        val caller = if (scenario == Scenario.SQUEEZE)
            Position.entries.firstOrNull { it.ordinal > villain.ordinal && it.ordinal < hero.ordinal } ?: return null
        else null
        val key = if (scenario == Scenario.RFI) SpotKey.of(scenario, hero, Position.BB)
        else SpotKey.of(scenario, hero, villain, caller)
        return key.takeIf { engine.repository[it] != null }
    }

    private fun refresh() {
        if (!::spStack.isInitialized || spStack.adapter == null) return
        val key = currentKey()
        if (key == null) {
            current = null; grid.strategy = null
            tvSummary.text = "Spot no válido"
            return
        }
        val ctx = PreflopContext(
            stake = Stake.entries[spStake.selectedItemPosition],
            effectiveStackBb = stacks[spStack.selectedItemPosition],
        )
        worker.execute {
            val s = engine.strategy(key, ctx)
            runOnUiThread {
                current = s
                grid.strategy = s
                tvSummary.text = String.format(
                    Locale.US, "%s | %s %.0fbb  raise %.1f%%  call %.1f%%  VPIP %.1f%%",
                    key, ctx.stake.name, ctx.effectiveStackBb,
                    s.raiseFrequency() * 100, s.callFrequency() * 100, s.vpip() * 100,
                )
            }
        }
    }

    private fun showHand(h: Int) {
        val s = current ?: return
        val m = s.mix(h)
        tvHand.text = String.format(
            Locale.US, "%s  R %.0f%%  C %.0f%%  F %.0f%%%s",
            HandClass.name(h), m.raise * 100, m.call * 100, m.fold * 100,
            if (s.isValue(h) && m.raise > 0f) "  [valor]" else if (m.raise > 0f) "  [farol]" else "",
        )
    }
}
