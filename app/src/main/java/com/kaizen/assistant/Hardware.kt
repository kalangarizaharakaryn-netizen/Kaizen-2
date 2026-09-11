package com.kaizen.assistant

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat

object Hardware {

    private var torchOn = false

    /** Returns a short status message so the caller can always tell the user what happened. */
    fun toggleTorch(context: Context): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "No torch-capable camera found on this device, ma'am."
            val next = !torchOn
            cameraManager.setTorchMode(camId, next)
            torchOn = next
            if (torchOn) "Torch on, ma'am." else "Torch off, ma'am."
        } catch (e: CameraAccessException) {
            "Couldn't reach the torch — the camera may be in use elsewhere."
        } catch (e: Exception) {
            "Torch control failed: ${e.message}"
        }
    }

    fun vibrate(context: Context) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
        }
    }

    /** Returns a short status message so the caller can always tell the user what happened. */
    fun requestEnableBluetooth(activity: Activity, launcher: ActivityResultLauncher<Intent>): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return "I need Bluetooth permission first, ma'am — check the permission prompt, or enable it in your phone's Settings for this app."
            }
        }
        return try {
            val manager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = manager.adapter ?: return "This device doesn't support Bluetooth, ma'am."
            if (!adapter.isEnabled) {
                launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                "Requesting Bluetooth, ma'am."
            } else {
                "Bluetooth is already on, ma'am."
            }
        } catch (e: SecurityException) {
            "Bluetooth permission was denied, ma'am."
        } catch (e: Exception) {
            "Bluetooth control failed: ${e.message}"
        }
    }

    /** Android has not allowed apps to silently disable Bluetooth since API 33 — this opens
     *  the settings screen so the user can do it themselves, rather than pretending to. */
    fun openBluetoothSettingsForOff(context: Context): String {
        return try {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "Android doesn't let apps switch Bluetooth off directly, ma'am — I've opened the settings for you."
        } catch (e: Exception) {
            "Couldn't open Bluetooth settings: ${e.message}"
        }
    }

    /** Android has not allowed apps to toggle Wi-Fi directly since API 29 — this opens the
     *  quick panel (or settings, on older versions) so the user can flip it themselves. */
    fun openWifiSettings(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.startActivity(Intent(Settings.Panel.ACTION_WIFI))
            } else {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            "Android doesn't let apps switch Wi-Fi directly anymore, ma'am — I've opened the panel for you."
        } catch (e: Exception) {
            "Couldn't open Wi-Fi settings: ${e.message}"
        }
    }
}
