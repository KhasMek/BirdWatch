# BirdWatch R8 rules for the minified release build.
#
# Room, kotlinx-serialization, Play Services (Maps/Location), usb-serial-for-android and the
# AndroidX libraries all ship their own consumer keep rules, and `proguard-android-optimize.txt`
# already keeps enums, Parcelables and native methods. The rules below cover only what is
# specific to this app.

# Readable stack traces from sideloaded builds (no Play crash reporting). File names are hidden
# but line numbers survive, and mapping.txt is kept as a build artifact.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# Room stores enums by name via generated code; nothing reflective. The entity and DAOs are
# referenced directly, so no keep rules are needed. Enum *values* must keep their names though,
# because the database and exports contain them as strings: the default optimize config keeps
# `values()`/`valueOf()`, and R8 does not rename enum constants, so this is covered.

# kotlinx-serialization: we only use the JsonElement tree API (parseToJsonElement / buildJsonObject),
# no @Serializable classes, so no serializer keep rules apply.

# security-crypto pulls in Tink, which references optional annotation processors that are not on
# the runtime classpath. These are safe to ignore.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
