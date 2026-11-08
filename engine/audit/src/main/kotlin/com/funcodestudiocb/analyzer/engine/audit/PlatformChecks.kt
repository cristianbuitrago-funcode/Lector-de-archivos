package com.funcodestudiocb.analyzer.engine.audit

import com.funcodestudiocb.analyzer.engine.api.Certainty

/** Android en el código (WebView, errores SSL) y el uso de los permisos peligrosos declarados. */
internal object PlatformChecks {

    /** APIs que usan cada permiso peligroso: si ninguna aparece en el código, el permiso puede sobrar. */
    val PERMISSION_USAGE: Map<String, List<String>> = mapOf(
        "CAMERA" to listOf("Camera", "camera", "ImageCapture", "ACTION_IMAGE_CAPTURE", "ACTION_VIDEO_CAPTURE", "CameraX", "barcode", "Barcode", "scanner"),
        "RECORD_AUDIO" to listOf("AudioRecord", "MediaRecorder", "SpeechRecognizer", "RECORD_AUDIO", "microphone", "Microphone"),
        "ACCESS_FINE_LOCATION" to listOf("LocationManager", "FusedLocation", "getLastLocation", "requestLocationUpdates", "geolocator", "Geolocation", "location"),
        "ACCESS_COARSE_LOCATION" to listOf("LocationManager", "FusedLocation", "getLastLocation", "requestLocationUpdates", "geolocator", "Geolocation", "location"),
        "ACCESS_BACKGROUND_LOCATION" to listOf("LocationManager", "FusedLocation", "requestLocationUpdates", "Geofenc", "geolocator", "location"),
        "READ_CONTACTS" to listOf("ContactsContract", "contacts", "Contacts"),
        "WRITE_CONTACTS" to listOf("ContactsContract", "contacts", "Contacts"),
        "GET_ACCOUNTS" to listOf("AccountManager", "getAccounts"),
        "READ_SMS" to listOf("Telephony.Sms", "content://sms", "SmsMessage", "sms"),
        "SEND_SMS" to listOf("SmsManager", "sendTextMessage", "sms"),
        "RECEIVE_SMS" to listOf("SMS_RECEIVED", "SmsMessage", "SmsRetriever", "sms"),
        "READ_CALL_LOG" to listOf("CallLog"),
        "WRITE_CALL_LOG" to listOf("CallLog"),
        "CALL_PHONE" to listOf("ACTION_CALL", "TelecomManager", "placeCall"),
        "READ_PHONE_STATE" to listOf("TelephonyManager", "getDeviceId", "getLine1Number", "PhoneStateListener", "TelephonyCallback"),
        "READ_PHONE_NUMBERS" to listOf("getLine1Number", "TelephonyManager", "SubscriptionManager"),
        "READ_CALENDAR" to listOf("CalendarContract", "calendar"),
        "WRITE_CALENDAR" to listOf("CalendarContract", "calendar"),
        "BODY_SENSORS" to listOf("SensorManager", "TYPE_HEART_RATE", "HealthConnect", "sensor"),
        "ACTIVITY_RECOGNITION" to listOf("ActivityRecognition", "TYPE_STEP_COUNTER", "TYPE_STEP_DETECTOR"),
        "READ_EXTERNAL_STORAGE" to listOf("getExternalStorage", "MediaStore", "Environment.DIRECTORY", "ACTION_PICK", "ACTION_GET_CONTENT", "file_picker", "image_picker", "Storage"),
        "WRITE_EXTERNAL_STORAGE" to listOf("getExternalStorage", "MediaStore", "Environment.DIRECTORY", "FileOutputStream", "Storage"),
        "MANAGE_EXTERNAL_STORAGE" to listOf("isExternalStorageManager", "MANAGE_ALL_FILES_ACCESS", "getExternalStorage"),
        "READ_MEDIA_IMAGES" to listOf("MediaStore", "ACTION_PICK", "image_picker", "PickVisualMedia", "Images"),
        "READ_MEDIA_VIDEO" to listOf("MediaStore", "ACTION_PICK", "PickVisualMedia", "Video"),
        "READ_MEDIA_AUDIO" to listOf("MediaStore", "Audio"),
        "SYSTEM_ALERT_WINDOW" to listOf("TYPE_APPLICATION_OVERLAY", "canDrawOverlays", "ACTION_MANAGE_OVERLAY_PERMISSION"),
        "REQUEST_INSTALL_PACKAGES" to listOf("PackageInstaller", "application/vnd.android.package-archive", "ACTION_INSTALL_PACKAGE"),
        "QUERY_ALL_PACKAGES" to listOf("getInstalledPackages", "getInstalledApplications", "queryIntentActivities"),
        "BLUETOOTH_SCAN" to listOf("BluetoothLeScanner", "startDiscovery", "startScan", "Bluetooth"),
        "NEARBY_WIFI_DEVICES" to listOf("WifiP2pManager", "WifiAwareManager", "WifiManager"),
        "USE_SIP" to listOf("SipManager"),
        "ANSWER_PHONE_CALLS" to listOf("acceptRingingCall", "TelecomManager"),
        "BIND_ACCESSIBILITY_SERVICE" to listOf("AccessibilityService"),
        "PROCESS_OUTGOING_CALLS" to listOf("NEW_OUTGOING_CALL"),
    )
    private val ALL_USAGE = PERMISSION_USAGE.values.flatten().distinct()
    private val MOBILE_LANGUAGES = setOf("java", "kotlin", "dart", "javascript", "typescript", "tsx", "csharp")

    fun check(a: FileAudit) {
        val text = a.text
        val usage = a.sink.signals.permissionUsage
        val mobile = a.language in MOBILE_LANGUAGES &&
            (text.contains("android") || text.contains("Android") || text.contains("permission") || text.contains("Permission"))
        if (mobile && usage.size < ALL_USAGE.size) {
            for (word in ALL_USAGE) if (word !in usage && text.contains(word)) usage += word
            // Uso directo del permiso (petición en tiempo de ejecución: Manifest.permission.CAMERA, Permission.camera…).
            for (perm in PERMISSION_USAGE.keys) if ("perm:$perm" !in usage && text.contains(perm)) usage += "perm:$perm"
        }
        if (a.family != "jvm") return
        if (text.contains("addJavascriptInterface(")) {
            val jsEnabled = text.contains("setJavaScriptEnabled(true)") || text.contains("javaScriptEnabled = true") || text.contains("javaScriptEnabled=true")
            a.src.each("addJavascriptInterface(") { at ->
                a.addAt(
                    "FC-WEBVIEW-01", at, if (jsEnabled) Certainty.CONFIRMED else Certainty.POSSIBLE,
                    if (jsEnabled) "El WebView activa JavaScript y expone un objeto nativo con addJavascriptInterface." else "Se expone un objeto nativo con addJavascriptInterface; no se ve en este archivo si JavaScript está activo.",
                )
            }
        }
        if (text.contains("onReceivedSslError") && text.contains(".proceed()")) {
            a.src.each(".proceed()") { at ->
                val line = a.src.line(at)
                if (a.near(line, listOf("onReceivedSslError"), before = 15, after = 0) != null) {
                    a.addAt("FC-TLS-01", at, Certainty.CONFIRMED, "onReceivedSslError continúa (handler.proceed()) a pesar del error de certificado.")
                }
            }
        }
    }
}
