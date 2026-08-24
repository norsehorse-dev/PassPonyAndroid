# PassPony release (R8) keep rules.
#
# The release build sets isMinifyEnabled = true. The crypto/storage layer
# reaches Rust through UniFFI, which is generated as *pure JNA* bindings, and
# the OpenPGP path runs on BouncyCastle. Both bind to code by REFLECTION at
# runtime -- JNA marshals Structure fields by declared order and looks up
# native functions by the Library interface's method names, while BouncyCastle
# resolves providers and algorithm implementations by class name. R8 renaming
# or removing any of it makes the very first FFI call throw on launch (the app
# builds and runs fine unminified, which is why a debug build never shows it).
# Do not delete these without moving the whole crypto stack off reflection.

# ---- JNA (net.java.dev.jna) ----
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keepclassmembers class * implements com.sun.jna.Callback { *; }
-dontwarn java.awt.**

# ---- UniFFI-generated bindings (pass-ffi) ----
# Structure subclasses with @JvmField fields read by field order, a
# com.sun.jna.Library whose method names ARE the Rust FFI symbol names, and
# Callback interfaces invoked from native. Keep the package verbatim.
-keep class uniffi.pass_ffi.** { *; }
-keep interface uniffi.pass_ffi.** { *; }

# ---- BouncyCastle (bcprov/bcpg, via PGPonyCore-Kotlin) ----
-keep class org.bouncycastle.** { *; }
-keep interface org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ---- PGPonyCore-Kotlin (OpenPGP engine + smartcard services) ----
-keep class com.pgpony.android.crypto.** { *; }
