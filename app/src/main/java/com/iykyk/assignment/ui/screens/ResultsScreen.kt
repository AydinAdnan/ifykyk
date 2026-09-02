package com.iykyk.assignment.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iykyk.assignment.domain.model.AnalysisResult
import com.iykyk.assignment.ui.components.*
import com.iykyk.assignment.ui.theme.*

@Composable
fun ResultsScreen(
    result: AnalysisResult,
    onViewCollage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chipColors = listOf(ChipPurple, ChipYellow, ChipGreen, ChipPink, ChipBlue)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ScrapbookCanvas)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // 1. Green Ripped Header: "RESULTS ♡"
        Box(modifier = Modifier.fillMaxWidth()) {
            BrutalCard(
                backgroundColor = CardGreen,
                rotation = -1.2f,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "RESULTS",
                        fontSize = 32.sp,
                        fontFamily = CherryBombOneFamily,
                        color = TextPrimary
                    )
                    HeartDoodle()
                }
            }

            WashiTapeDecoration(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-10).dp, y = (-10).dp),
                isPink = false,
                rotation = -12f
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 2. Stat Cards: UNIQUE PEOPLE and TOTAL APPEARANCES
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Purple Card: UNIQUE PEOPLE
            Box(modifier = Modifier.weight(1f)) {
                BrutalCard(
                    backgroundColor = CardPurple,
                    rotation = -1f,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "UNIQUE PEOPLE",
                            fontSize = 12.sp,
                            fontFamily = GoogleSansFamily,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val countStr = String.format("%02d", result.totalUniquePeople)
                        Text(
                            text = countStr,
                            fontSize = 42.sp,
                            fontFamily = CherryBombOneFamily,
                            color = TextPrimary
                        )
                    }
                }
            }

            // Pink Card: TOTAL APPEARANCES
            Box(modifier = Modifier.weight(1f)) {
                BrutalCard(
                    backgroundColor = CardPink,
                    rotation = 1.2f,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "TOTAL APPEARANCES",
                            fontSize = 11.sp,
                            fontFamily = GoogleSansFamily,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val appStr = String.format("%02d", result.totalAppearances)
                        Text(
                            text = appStr,
                            fontSize = 42.sp,
                            fontFamily = CherryBombOneFamily,
                            color = TextPrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 3. PEOPLE FOUND Avatar Row
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "PEOPLE FOUND",
                fontSize = 16.sp,
                fontFamily = CherryBombOneFamily,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                result.clusters.forEachIndexed { idx, cluster ->
                    val color = chipColors[idx % chipColors.size]

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Avatar circle with colored border
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(color.copy(alpha = 0.2f))
                                .border(2.5.dp, color, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            val repBmp = cluster.representativeShot.generousCropBitmap ?: cluster.representativeShot.alignedCropBitmap
                            if (repBmp != null) {
                                Image(
                                    bitmap = repBmp.asImageBitmap(),
                                    contentDescription = cluster.personLabel,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Count chip e.g. "04"
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(color)
                                .border(1.5.dp, BrutalBorder, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            val chipText = String.format("%02d", cluster.appearanceCount)
                            Text(
                                text = chipText,
                                fontSize = 12.sp,
                                fontFamily = GoogleSansFamily,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 4. Blue Note Summary
        Box(modifier = Modifier.fillMaxWidth()) {
            BrutalCard(
                backgroundColor = CardBlue,
                rotation = -0.5f,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Great!  unique people found with  total appearances. \uD83D\uDE42",
                    fontSize = 15.sp,
                    fontFamily = GoogleSansFamily,
                    color = TextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 5. Green Action Button: VIEW COLLAGE
        BrutalButton(
            text = "VIEW COLLAGE",
            onClick = onViewCollage,
            backgroundColor = CardGreen,
            trailingIcon = {
                Icon(Icons.Default.ArrowOutward, contentDescription = null, tint = TextPrimary)
            }
        )

        Spacer(modifier = Modifier.height(48.dp))
    }
}
