# This file previously didn't exist even though build.gradle referenced it — harmless while
# minifyEnabled was false, but would have been a problem the moment shrinking was turned on.

# Room's own consumer ProGuard rules (bundled in the AAR) already keep @Entity/@Dao/@Database
# classes. These rules are extra, conservative insurance for the two third-party UI libraries
# this app can't easily be smoke-tested against release/R8 builds on a real device.
-keep class com.github.mikephil.charting.** { *; }
-keep interface com.github.mikephil.charting.** { *; }
-keep class com.airbnb.lottie.** { *; }
-dontwarn com.github.mikephil.charting.**

# Transitively pulled in by okio (used internally by Lottie/MPAndroidChart). This is a
# JSR-305 compile-time-only annotation with no runtime presence needed — R8 generated this
# exact rule itself (build/outputs/mapping/release/missing_rules.txt) after minification was
# first turned on for this project; verified by re-running assembleRelease afterward.
-dontwarn javax.annotation.Nullable

# Data model classes crossing the Room boundary.
-keep class com.scrollguard.data.** { *; }

# WorkManager instantiates Workers (SyncWorker) reflectively by class name, resolved from its
# own internal work database — R8 can't trace that, so without this the release build could
# silently strip/rename SyncWorker, breaking the periodic parental-control safety-net sync with
# no crash, only a caught ClassNotFoundException inside WorkManager's own error handling.
-keep class com.scrollguard.parental.** { *; }
