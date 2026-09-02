package com.iykyk.assignment.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iykyk.assignment.domain.model.AnalysisResult
import com.iykyk.assignment.ui.components.*
import com.iykyk.assignment.ui.theme.*

@Composable
fun AppearanceBreakdownScreen(
    result: AnalysisResult,
    onBackToCollage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dotColors = listOf(ChipPurple, ChipYellow, ChipGreen, ChipPink, ChipBlue)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ScrapbookCanvas)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(28.dp))

        // 1. Blue Ripped Header: "APPEARANCE BREAKDOWN" in Cherry Bomb One
        Box(modifier = Modifier.fillMaxWidth()) {
            BrutalCard(
                backgroundColor = CardBlue,
                rotation = -1f,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "APPEARANCE\nBREAKDOWN",
                        fontSize = 26.sp,
                        fontFamily = CherryBombOneFamily,
                        lineHeight = 30.sp,
                        color = TextPrimary
                    )
                    HeartDoodle()
                }
            }

            WashiTapeDecoration(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 12.dp, y = (-12).dp),
                isPink = false,
                rotation = 16f
            )

            WashiTapeDecoration(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-12).dp, y = 8.dp),
                isPink = true,
                rotation = -22f
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 2. Ledger Sheet (White Notepad Card)
        Box(modifier = Modifier.fillMaxWidth()) {
            BrutalCard(
                backgroundColor = CardWhite,
                rotation = 0.5f,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    result.clusters.forEachIndexed { idx, cluster ->
                        val dotColor = dotColors[idx % dotColors.size]

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            // Colored bullet dot
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                                    .border(1.5.dp, BrutalBorder, CircleShape)
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            // Person name
                            Text(
                                text = cluster.personLabel,
                                fontSize = 15.sp,
                                fontFamily = GoogleSansFamily,
                                color = TextPrimary
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            // Dotted leader line
                            Text(
                                text = ". . . . . . . . . . . . . . . .",
                                fontSize = 14.sp,
                                color = TextMuted,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            // Appearance count chip
                            val countStr = String.format("%02d", cluster.appearanceCount)
                            Text(
                                text = countStr,
                                fontSize = 18.sp,
                                fontFamily = CherryBombOneFamily,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }

            // Smiley at bottom corner of ledger
            SmileySticker(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 8.dp, y = 16.dp),
                rotation = 5f
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 3. Purple Explanation Card: "WHAT IS AN APPEARANCE?"
        Box(modifier = Modifier.fillMaxWidth()) {
            BrutalCard(
                backgroundColor = CardPurple,
                rotation = -1.2f,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "WHAT IS AN APPEARANCE?",
                    fontSize = 16.sp,
                    fontFamily = CherryBombOneFamily,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "An appearance is counted when a person is visible in the frame. Multiple people in the same frame are counted separately.",
                    fontSize = 13.sp,
                    fontFamily = GoogleSansFamily,
                    lineHeight = 18.sp,
                    color = TextSecondary
                )
            }

            // Paperclip on top-left of purple card
            PaperclipDecoration(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-8).dp, y = (-16).dp),
                isPink = false,
                rotation = -8f
            )

            // Tape on top-right of purple card
            WashiTapeDecoration(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 12.dp, y = (-8).dp),
                isPink = true,
                rotation = 12f
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 4. Back Button
        BrutalButton(
            text = "BACK TO COLLAGE",
            onClick = onBackToCollage,
            backgroundColor = CardGreen
        )

        Spacer(modifier = Modifier.height(48.dp))
    }
}
