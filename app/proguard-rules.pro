# ─── kotlinx.serialization ───────────────────────────────────────────────────
# The data classes in domain.model / data are (de)serialized to JSON. R8 must
# keep their generated $serializer / Companion so reflection-free serialization
# keeps working after shrinking. (These mirror the rules in the library README;
# they are idempotent with the consumer rules the artifact already ships.)
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Keep `Companion` object fields of serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep the generated serializers for this app's models explicitly.
-keep,includedescriptorclasses class xyz.limo060719.goclaw.**$$serializer { *; }
-keepclassmembers class xyz.limo060719.goclaw.** {
    *** Companion;
}

# ─── Hilt / Dagger, OkHttp, Coil, DataStore ──────────────────────────────────
# These libraries ship their own consumer ProGuard rules, so no extra keeps are
# required here. OkHttp references optional TLS providers that aren't on Android;
# silence the warnings so a full-mode R8 build doesn't fail.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
