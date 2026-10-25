package org.opencode.mobile.ui.chat

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import org.opencode.mobile.R

// Доступные шрифты ответов модели: (ключ сохранения, имя в палитре, FontFamily)
internal val fontEntries =
    listOf(
        Triple("mono", "Моноширинный", FontFamily.Monospace),
        Triple("jbm", "JetBrains Mono", FontFamily(Font(R.font.jbm))),
        Triple("play", "Play", FontFamily(Font(R.font.play))),
        Triple("lobster", "Lobster", FontFamily(Font(R.font.lobster))),
        Triple("vt323", "VT323", FontFamily(Font(R.font.vt323))),
        Triple("caveat", "Caveat", FontFamily(Font(R.font.caveat))),
        Triple("montserrat", "Montserrat", FontFamily(Font(R.font.montserrat))),
        Triple("russo", "Russo One", FontFamily(Font(R.font.russo_one))),
        Triple("neucha", "Neucha", FontFamily(Font(R.font.neucha))),
        Triple("badscript", "Bad Script", FontFamily(Font(R.font.bad_script))),
        Triple("raleway", "Raleway", FontFamily(Font(R.font.raleway))),
        Triple("rubik", "Rubik", FontFamily(Font(R.font.rubik))),
        Triple("exo2", "Exo 2", FontFamily(Font(R.font.exo2))),
        Triple("ptsans", "PT Sans", FontFamily(Font(R.font.ptsans))),
        Triple("ptserif", "PT Serif", FontFamily(Font(R.font.ptserif))),
        Triple("dancing", "Dancing Script", FontFamily(Font(R.font.dancing))),
        Triple("comfortaa", "Comfortaa", FontFamily(Font(R.font.comfortaa))),
        Triple("kurale", "Kurale", FontFamily(Font(R.font.kurale))),
        Triple("pangolin", "Pangolin", FontFamily(Font(R.font.pangolin))),
        Triple("cormorant", "Cormorant", FontFamily(Font(R.font.cormorant))),
    )

internal fun fontFor(key: String): FontFamily = fontEntries.firstOrNull { it.first == key }?.third ?: FontFamily.Monospace
