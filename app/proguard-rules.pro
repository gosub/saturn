# Keep Gson model classes (serialized field names must not be renamed)
-keepclassmembers class it.lo.exp.saturn.AgentClient$* {
    <fields>;
}
-keepclassmembers class it.lo.exp.saturn.AgentClient {
    <fields>;
}

# Keep generic signatures for Gson TypeToken
-keepattributes Signature
-keepattributes *Annotation*

# Gson
-keep class com.google.gson.** { *; }
