# Room, Hilt and kotlinx.serialization ship their own consumer rules; nothing extra is
# needed for them. What R8 cannot see are the classes the *system* instantiates by name
# from the manifest, so they are kept explicitly.
-keep class com.sonoritmo.app.receiver.** { *; }
-keep class com.sonoritmo.app.tile.** { *; }
-keep class com.sonoritmo.app.widget.** { *; }
-keep class com.sonoritmo.core.system.service.** { *; }
# WatchdogWorker is instantiated by name by WorkManager and is not declared in the
# manifest, so R8 cannot see the reference. Losing it would break only the minified
# release build - the worst place to find out.
-keep class com.sonoritmo.core.system.scheduler.** { *; }
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# Domain enums are persisted by stable name and serialised into the backup JSON. Renaming
# one would silently invalidate every existing database row and every exported file.
-keepclassmembers enum com.sonoritmo.core.domain.model.** { *; }
