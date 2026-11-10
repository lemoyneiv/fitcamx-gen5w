# FITCAMX for Gen5W - R8 Shrinking & API 19 (KitKat Dalvik) Rules
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# Gen5W runs Android 4.4.2 KitKat (API 19). Folding SDK_INT == 19 and AndroidUtil's
# precomputed API booleans eliminates dead API 21+/23+ branches (such as
# android.media.AudioDeviceCallback in LibVLC MediaPlayer).
-assumevalues class android.os.Build$VERSION {
    public static final int SDK_INT return 19..19;
}
-assumevalues class org.videolan.libvlc.util.AndroidUtil {
    public static final boolean isPOrLater return false;
    public static final boolean isOOrLater return false;
    public static final boolean isNougatMR1OrLater return false;
    public static final boolean isNougatOrLater return false;
    public static final boolean isMarshMallowOrLater return false;
    public static final boolean isLolliPopOrLater return false;
    public static final boolean isKitKatOrLater return true;
    public static final boolean isJellyBeanMR2OrLater return true;
}

# Preserve all FITCAMX app classes and methods
-keep class fitcamx.app.** { *; }

# Preserve LibVLC interfaces, events, and Track/Slave/Stats structs constructed from JNI
-keep class org.videolan.libvlc.interfaces.** { *; }

# Preserve AWindow & native surface lock callbacks invoked by libvlc.so AWindowHandler
-keep class org.videolan.libvlc.AWindow { *; }
-keep class org.videolan.libvlc.AWindow$* { *; }

# Preserve JNI_OnLoad registered classes and native callback entrypoints in libvlcjni.so
-keep class org.videolan.libvlc.VLCObject { *; }
-keep class org.videolan.libvlc.LibVLC { *; }
-keep class org.videolan.libvlc.LibVLC$* { *; }
-keep class org.videolan.libvlc.Media { *; }
-keep class org.videolan.libvlc.MediaList { *; }
-keep class org.videolan.libvlc.Dialog { *; }
-keep class org.videolan.libvlc.Dialog$* { *; }
-keep class org.videolan.libvlc.MediaDiscoverer { *; }
-keep class org.videolan.libvlc.MediaDiscoverer$* { *; }
-keep class org.videolan.libvlc.RendererDiscoverer { *; }
-keep class org.videolan.libvlc.RendererDiscoverer$* { *; }
-keep class org.videolan.libvlc.RendererItem { *; }
-keep class org.videolan.libvlc.RendererItem$* { *; }

# Preserve MediaPlayer public API & JNI hooks, allowing dead API-23 AudioDeviceCallback inner class to be stripped
-keep class org.videolan.libvlc.MediaPlayer {
    public <init>(...);
    public <methods>;
    native <methods>;
    protected <methods>;
    private static *** *FromNative(...);
}
-keep class org.videolan.libvlc.MediaPlayer$Chapter { *; }
-keep class org.videolan.libvlc.MediaPlayer$Equalizer { *; }
-keep class org.videolan.libvlc.MediaPlayer$Title { *; }
-keep class org.videolan.libvlc.MediaPlayer$TrackDescription { *; }
-keep class org.videolan.libvlc.MediaPlayer$Event { *; }
-keep class org.videolan.libvlc.MediaPlayer$EventListener { *; }
-keep class org.videolan.libvlc.MediaPlayer$ScaleType { *; }

# Preserve any native methods across remaining reachable LibVLC helpers
-keepclasseswithmembernames,includedescriptorclasses class org.videolan.** {
    native <methods>;
}

# Suppress warnings for post-API-19 Android SDK & optional Jetpack UI classes stripped from libvlc.jar
-dontwarn android.media.AudioDeviceCallback
-dontwarn android.media.AudioDeviceInfo
-dontwarn android.media.AudioAttributes**
-dontwarn android.media.AudioFocusRequest**
-dontwarn android.media.MediaRouter**
-dontwarn android.graphics.SurfaceTexture**
-dontwarn android.system.**
-dontwarn android.view.Display**
-dontwarn androidx.**
-dontwarn android.support.**
