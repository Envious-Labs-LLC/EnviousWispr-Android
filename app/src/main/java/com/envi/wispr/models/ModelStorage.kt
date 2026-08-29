package com.envi.wispr.models

import android.content.Context
import java.io.File

/** Shared app-private model paths for the main process and isolated model processes. */
object ModelStorage {
    fun root(context: Context): File = File(context.applicationContext.noBackupFilesDir, "models")
    fun directory(context: Context, model: ModelDescriptor): File = File(root(context), model.id)
    fun isReady(context: Context, model: ModelDescriptor): Boolean = ModelDeliveryStore(root(context)).isVerified(model)
}
