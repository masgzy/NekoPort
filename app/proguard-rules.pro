# NekoPort keeps minify disabled by default; rules below keep serialization working
# if someone enables R8 later.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class io.github.masgzy.nekoport.**$$serializer { *; }
-keepclassmembers class io.github.masgzy.nekoport.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.masgzy.nekoport.** {
    kotlinx.serialization.KSerializer serializer(...);
}
