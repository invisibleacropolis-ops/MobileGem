package com.mobilegem.gemma

import android.content.Context
import com.mobilegem.gemma.inference.InferenceController
import com.mobilegem.gemma.model.ModelFileManager
import com.mobilegem.gemma.settings.SettingsRepository
import java.io.File

class AppContainer(context: Context) {
    val settingsRepository = SettingsRepository(context)
    val modelFileManager = ModelFileManager(File(context.filesDir, "models"))
    val inferenceController = InferenceController()
}
