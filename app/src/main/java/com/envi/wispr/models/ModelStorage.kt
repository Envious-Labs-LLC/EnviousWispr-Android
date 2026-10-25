package com.envi.wispr.models

import android.content.Context
import java.io.File

/** Shared app-private model paths for the main process and isolated model processes. */
internal object ModelStorage {
    fun root(context: Context): File = File(context.applicationContext.noBackupFilesDir, "models")
    fun directory(context: Context, model: ModelDescriptor): File = File(root(context), model.id)
    /** The CURRENT revision is installed and verified: the question for cards, downloads and updates. */
    fun isReady(context: Context, model: ModelDescriptor): Boolean = ModelDeliveryStore(root(context)).isVerified(model)

    /** A pinned revision this build can load is installed and verified (#108): the question for "can I dictate". */
    fun usable(context: Context, model: ModelDescriptor): ModelDescriptor? = ModelDeliveryStore(root(context)).usableDescriptor(model)
}
