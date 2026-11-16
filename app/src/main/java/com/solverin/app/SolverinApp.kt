package com.solverin.app

import android.app.Application
import com.solverin.engine.preflop.PreflopEngine

class SolverinApp : Application() {
    val engine: PreflopEngine by lazy { PreflopEngine() }
}
