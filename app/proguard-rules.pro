# SMS Relay ProGuard rules
# Keep Gson serialization classes
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.ohmyjapan.smsrelay.TriggerRule { *; }
-keep class com.ohmyjapan.smsrelay.LogEntry { *; }
