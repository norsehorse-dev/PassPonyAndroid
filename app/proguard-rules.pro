# PassPony release (R8) keep rules.
#
# The release build sets isMinifyEnabled = true. The crypto/storage layer
# reaches Rust through UniFFI, which is generated as *pure JNA* bindings.
# JNA binds to code by REFLECTION at runtime: it marshals Structure fields by
# declared order and looks up native functions by the Library interface's
# method names. R8 renaming or removing any of it makes the very first FFI
# call throw on launch (the app builds and runs fine unminified, which is
# why a debug build never shows it).
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

# ---- OpenPGP provider API (third_party/openpgp-api) ----
# The parcelables and the AIDL interface cross the process boundary by
# class name. PGPony Android 4.0.0 to 4.0.3 shipped a provider that worked
# in debug and crashed clients in release because R8 renamed one.
-keep class org.openintents.openpgp.** { *; }
-keep interface org.openintents.openpgp.** { *; }
