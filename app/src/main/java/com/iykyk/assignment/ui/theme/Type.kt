package com.iykyk.assignment.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.iykyk.assignment.R

val CherryBombOneFamily = FontFamily(
    Font(R.font.cherry_bomb_one, FontWeight.Normal)
)

val GoogleSansFamily = FontFamily(
    Font(R.font.google_sans, FontWeight.Normal),
    Font(R.font.google_sans, FontWeight.Medium),
    Font(R.font.google_sans, FontWeight.Bold),
    Font(R.font.google_sans, FontWeight.Black)
)

val Typography = Typography(
    headlineLarge = TextStyle(
        fontFamily = CherryBombOneFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 38.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = CherryBombOneFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 26.sp,
        lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontFamily = GoogleSansFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = GoogleSansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = GoogleSansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = GoogleSansFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 0.5.sp
    )
)