-dontwarn org.immutables.value.Value$Default
-dontwarn org.immutables.value.Value$Immutable
-dontwarn org.immutables.value.Value$Style$BuilderVisibility
-dontwarn org.immutables.value.Value$Style$ImplementationVisibility
-dontwarn org.immutables.value.Value$Style

-keep class com.antonkarpenko.ffmpegkit.** { *; }
-keepclasseswithmembernames class com.antonkarpenko.ffmpegkit.** {
    native <methods>;
}
-keepattributes Exceptions, InnerClasses, Signature, Deprecated, SourceFile, LineNumberTable, *Annotation*, EnclosingMethod
-keep class com.yausername.youtubedl_android.** { *; }
-keep class org.immutables.** { *; }
-keep class com.myAllVideoBrowser.ui.main.home.browser.adblocker.AdBlockNative {
    native <methods>;
}

-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
-dontwarn java.beans.XMLIDREF
-dontwarn com.fasterxml.jackson.databind.ext.Java7SupportImpl