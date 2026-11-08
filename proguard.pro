-dontshrink
-dontoptimize
-ignorewarnings
-dontwarn **
-dontnote **

-overloadaggressively
-dontusemixedcaseclassnames
-renamesourcefileattribute ''
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,MethodParameters

-keep class com.btemu.BtEmu { *; }
-keep class com.btemu.client.BtEmuClient { *; }

-keep class com.btemu.mixin.** { *; }

-keep class org.cef.** { *; }
-keep class org.lwjgl.sdl.** { *; }
-keepclassmembers class * extends org.cef.browser.CefBrowser_N { *; }

-keep interface com.btemu.emu.RetroCore$* { *; }
-keepclassmembers class * implements com.sun.jna.Callback { *; }

-keepclassmembers class * extends net.minecraft.core.block.entity.TileEntity { <init>(); }
-keepclassmembers class * implements turniplabs.halplibe.helper.network.NetworkMessage { <init>(); }

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
