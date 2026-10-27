package com.passpony.android.ui.theme

import androidx.compose.ui.graphics.Color

// PassPony's brand is the orange of its app icon (#FF7A1E, the adaptive
// icon background in res/values/colors.xml). Orange is used for the
// primary action, focus, links and selection only; every surface is a
// warm neutral. Every Material color role is pinned in Theme.kt so no
// component ever falls back to the Material baseline palette.

// Brand
val PonyOrange = Color(0xFFFF7A1E)
val PonyOrangeDeep = Color(0xFFA94700) // light-mode primary, AA on white
val PonyOrangeLight = Color(0xFFFF9A55) // dark-mode primary
val PonyOrangeTintLight = Color(0xFFFFDBC8)
val PonyOrangeTintDark = Color(0xFF7A3300)

// Light neutrals
val LightGround = Color(0xFFFBF8F6)
val LightInk = Color(0xFF1F1B18)
val LightMuted = Color(0xFF57504A)
val LightLine = Color(0xFFDAD2CC)
val LightOutline = Color(0xFF8A817A)

// Dark neutrals
val DarkGround = Color(0xFF161311)
val DarkInk = Color(0xFFEAE1DC)
val DarkMuted = Color(0xFFCFC5BE)
val DarkLine = Color(0xFF4A433D)
val DarkOutline = Color(0xFF988F88)

// Status (used on an icon and its status word only)
val StatusAmberLight = Color(0xFF8A5A00)
val StatusAmberDark = Color(0xFFFFC266)
