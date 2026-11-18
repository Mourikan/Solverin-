package com.solverin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.solverin.app.presentation.SolverViewModel
import com.solverin.app.ui.CakeTheme
import com.solverin.app.ui.SolverScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CakeTheme {
                val vm: SolverViewModel = viewModel()
                SolverScreen(vm)
            }
        }
    }
}
