-dontobfuscate
-dontoptimize
-keepattributes *
-keep class app.revanced.** {
  *;
}
-keep class com.google.** {
  *;
}
# Two-finger swipe: the Nintype engine is shrunk to what the extension reaches. The search host
# is passed explicitly, but the pipeline can also find it by name.
-keep class nintype.engine.search.PipelineMergeHost { *; }
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.**
