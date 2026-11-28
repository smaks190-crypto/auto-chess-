package dev.nox.local

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/** Fail closed when the display or lock state cannot be established. */
internal object DeviceInteraction {
    fun lockedPanel(context: Context): Boolean = runCatching {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        LockedAssistantPolicy.sessionAllowed(true, AssistantHandoff.active(context),
            context.getSystemService(PowerManager::class.java)?.isInteractive,
            keyguard?.isKeyguardLocked, keyguard?.isDeviceLocked)
    }.getOrDefault(false)

    fun unlocked(context: Context): Boolean = runCatching {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        BackgroundVoicePolicy.screenAllowsCommands(
            context.getSystemService(PowerManager::class.java)?.isInteractive,
            keyguard?.isKeyguardLocked, keyguard?.isDeviceLocked)
    }.getOrDefault(false)
}
