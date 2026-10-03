# The generated bindings reach the native library through JNA, which finds
# classes, fields and methods by reflection.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class io.github.dmitryweiner.synesthesia.core.** { *; }
-dontwarn java.awt.**
