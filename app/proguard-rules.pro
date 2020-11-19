-optimizationpasses 5
-dontusemixedcaseclassnames
-verbose

-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference
-keep public class com.jamal2367.styx.reading.*

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** w(...);
    public static *** i(...);
}

# this will fix a force close in ReadingActivity
-keep public class org.jsoup.** {
    public *;
}

# Without this rule, openFileChooser does not get called on KitKat
-keep class com.jamal2367.styx.view.LightningView$LightningChromeClient {
    void openFileChooser(android.webkit.ValueCallback);
    void openFileChooser(android.webkit.ValueCallback, java.lang.String);
    void openFileChooser(android.webkit.ValueCallback, java.lang.String, java.lang.String);
}

-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keepclassmembers class * extends android.app.Activity {
   public void *(android.view.View);
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# The support library contains references to newer platform versions.
# Don't warn about those in case this app is linking against an older
# platform version.  We know about them, and they are safe.
-dontwarn android.support.**

# The I2P Java API bundled inside the I2P Android client library contains
# references to javax.naming classes that Android doesn't have. But those
# classes are never used on Android, and it is safe to ignore the warnings.
-dontwarn net.i2p.crypto.CertUtil
-dontwarn org.apache.http.conn.ssl.DefaultHostnameVerifier

-dontwarn org.apache.http.HttpHost

# Needed for okhttp
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.ParametersAreNonnullByDefault
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
# A resource is loaded with a relative path so the package of this class must be preserved.
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
