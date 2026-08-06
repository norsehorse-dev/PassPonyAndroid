package com.passpony.android

import android.app.Application

/**
 * No global state lives here yet. Later packets hang the store engine
 * provider, unlock gate, and language manager off this class the same way
 * PGPonyApp does on the sibling app; kept as a bare Application subclass
 * now so the manifest's android:name reference has somewhere to point.
 */
class PassPonyApp : Application()
