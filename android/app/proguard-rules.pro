# JGit references desktop-JVM classes that don't exist on Android; they are never reached.
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn javax.**
-dontwarn java.lang.management.**
-dontwarn org.ietf.jgss.**
-dontwarn com.jcraft.**
-dontwarn com.googlecode.javaewah.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class dev.pact.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
