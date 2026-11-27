# openpgp-api (vendored)

The client side of the OpenPGP provider API that OpenKeychain defined and
PGPony Android also implements: the `IOpenPgpService2` AIDL, its
parcelables, and the `OpenPgpApi` helper. PassPony uses it to hand pass
(OpenPGP) decryption and encryption to a key app, so the keys never have
to be imported into PassPony.

Upstream: https://github.com/open-keychain/openpgp-api, Apache License 2.0
(each file carries its own header). Upstream publishes through JitPack;
the source is vendored here so the F-Droid and reproducible builds take
no prebuilt binary.

The files are the same copy PGPony Android ships
(`app/src/main/aidl` and `app/src/main/java/org/openintents/openpgp` in
that repo, at 4.8.2), so the two apps agree on the wire types. Nothing in
them is modified.

R8 must not rename these classes: the parcelables cross the process
boundary by class name. `app/proguard-rules.pro` keeps the package.
