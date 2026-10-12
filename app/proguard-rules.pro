# Tesseract4Android uses JNI callbacks into these classes.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.smartcash.analyzer.** {
    *** Companion;
}
-keepclasseswithmembers class com.smartcash.analyzer.** {
    kotlinx.serialization.KSerializer serializer(...);
}
