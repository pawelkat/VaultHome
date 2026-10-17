# Manifest entry points (activity, WearableListenerService, TileService) are kept by AAPT's
# generated rules; these make the intent explicit and keep names stable for adb/dumpsys checks.
-keep class com.vaulthome.wear.data.PanelListenerService { <init>(); }
-keep class com.vaulthome.wear.tile.PanelTileService { <init>(); }
-keep class com.vaulthome.wear.presentation.PanelActivity { <init>(); }
# Debug builds: keep readable stack traces.
-keepattributes SourceFile,LineNumberTable
