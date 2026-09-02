package com.iykyk.assignment.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iykyk.assignment.domain.model.AnalysisResult
import com.iykyk.assignment.ui.components.*
import com.iykyk.assignment.ui.theme.*

@Composable
fun CollagePreviewScreen(
    result: AnalysisResult,
    onSaveAndShare: (Bitmap) -> Unit,
    onViewBreakdown: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ScrapbookCanvas)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(28.dp))

        // 1. Top Badge: "COLLAGE PREVIEW" with paperclip
        Box(
            modifier = Modifier.wrapContentSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardPink)
                    .border(2.dp, BrutalBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "COLLAGE PREVIEW",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = TextPrimary
                )
            }

            PaperclipDecoration(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 16.dp, y = (-12).dp),
                isPink = true,
                rotation = -4f
            )

            HeartDoodle(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 44.dp, y = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 2. Stamp Grid Layout (Top 3 stamps + Bottom 2 stamps for 5 people)
        val clusters = result.clusters
        if (clusters.isNotEmpty()) {
            // First Row (up to 3 stamps)
            val firstRowCount = minOf(3, clusters.size)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (i in 0 until firstRowCount) {
                    val cluster = clusters[i]
                    val rot = if (i % 2 == 0) -2f else 2f
                    Box(modifier = Modifier.weight(1f)) {
                        StampCard(
                            imageModel = cluster.representativeShot.generousCropBitmap ?: cluster.representativeShot.alignedCropBitmap,
                            label = cluster.personLabel,
                            badgeText = "",
                            rotation = rot,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Second Row (remaining stamps)
            if (clusters.size > 3) {
                val remainingCount = clusters.size - 3
                Row(
                    modifier = Modifier.fillMaxWidth(if (remainingCount == 2) 0.72f else 1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for (i in 3 until clusters.size) {
                        val cluster = clusters[i]
                        val rot = if (i % 2 == 0) 2f else -2f
                        Box(modifier = Modifier.weight(1f)) {
                            StampCard(
                                imageModel = cluster.representativeShot.generousCropBitmap ?: cluster.representativeShot.alignedCropBitmap,
                                label = cluster.personLabel,
                                badgeText = "",
                                rotation = rot,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 3. Purple Caption Sticky Note
        Box(modifier = Modifier.fillMaxWidth()) {
            BrutalCard(
                backgroundColor = CardPurple,
                rotation = -0.8f,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Best shots.\nHappy faces.\nMemories together. \uD83D\uDE42",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 22.sp,
                    color = TextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 4. Action Buttons
        BrutalButton(
            text = "SAVE & SHARE COLLAGE",
            onClick = {
                val bmp = result.collageBitmap
                if (bmp != null) {
                    onSaveAndShare(bmp)
                }
            },
            backgroundColor = CardGreen,
            trailingIcon = {
                Icon(Icons.Default.Share, contentDescription = null, tint = TextPrimary)
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        BrutalButton(
            text = "VIEW BREAKDOWN",
            onClick = onViewBreakdown,
            backgroundColor = CardYellow,
            trailingIcon = {
                Icon(Icons.Default.List, contentDescription = null, tint = TextPrimary)
            }
        )

        Spacer(modifier = Modifier.height(48.dp))
    }
}
