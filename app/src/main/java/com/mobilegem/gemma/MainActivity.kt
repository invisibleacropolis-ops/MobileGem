package com.mobilegem.gemma

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.Factory
import com.mobilegem.gemma.ui.memory.MemoryViewModel
import com.mobilegem.gemma.ui.navigation.AppScaffold
import com.mobilegem.gemma.ui.settings.SettingsViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as GemmaApp).container

        val settingsFactory = object : Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(
                    settingsRepository = container.settingsRepository,
                    modelFileManager = container.modelFileManager,
                    inferenceController = container.inferenceController,
                ) as T
        }
        val settingsViewModel =
            ViewModelProvider(this, settingsFactory)[SettingsViewModel::class.java]

        val memoryFactory = object : Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MemoryViewModel(
                    memoryRepository = container.memoryRepository,
                    skillRepository = container.skillRepository,
                    longTermMemoryRepository = container.longTermMemoryRepository,
                    activeSessionHolder = container.activeSessionHolder,
                    extractorProvider = container::selfLearningExtractor,
                ) as T
        }
        val memoryViewModel =
            ViewModelProvider(this, memoryFactory)[MemoryViewModel::class.java]

        // Load the previously-selected model on startup.
        settingsViewModel.loadActive()

        setContent {
            MaterialTheme {
                AppScaffold(
                    settingsViewModel = settingsViewModel,
                    memoryViewModel = memoryViewModel,
                    activeSessionHolder = container.activeSessionHolder,
                )
            }
        }
    }
}
