package com.iykyk.assignment.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iykyk.assignment.domain.model.PipelineProgress
import com.iykyk.assignment.ui.components.*
import com.iykyk.assignment.ui.theme.*

@Composable
fun ProcessingScreen(
    progress: PipelineProgress,
    modifier: Modifier = Modifier
) {
    val animatedPercent by animateFloatAsState(
        targetValue = progress.progressPercent.toFloat(),
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "progressAnimation"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GridPaperLavender)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(36.dp))

        // 1. Top Badge: "PROCESSING..." in Cherry Bomb One font
        Box(
            modifier = Modifier.wrapContentSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardPurple)
                    .border(2.5.dp, BrutalBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 28.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "PROCESSING...",
                    fontSize = 24.sp,
                    fontFamily = CherryBombOneFamily,
                    color = TextPrimary
                )
            }

            PaperclipDecoration(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 14.dp, y = (-10).dp),
                isPink = true,
                rotation = 6f
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 2. Center Face Avatar Card with Border & Status
        Box(modifier = Modifier.fillMaxWidth()) {
            BrutalCard(
                backgroundColor = CardWhite,
                rotation = -0.5f,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // Circular Face Preview
                    Box(
                        modifier = Modifier
                            .size(130.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFEDE9FE))
                            .border(3.dp, BrutalBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        val faceBmp = progress.currentFaceBitmap
                        if (faceBmp != null) {
                            Image(
                                bitmap = faceBmp.asImageBitmap(),
                                contentDescription = "Active Face",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                text = "\uD83D\uDC64",
                                fontSize = 48.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Current Stage Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(CardYellow)
                            .border(2.dp, BrutalBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = progress.currentStep.title.uppercase(),
                            fontSize = 13.sp,
                            fontFamily = GoogleSansFamily,
                            color = TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Status details
                    Text(
                        text = progress.statusMessage,
                        fontSize = 14.sp,
                        fontFamily = GoogleSansFamily,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            WashiTapeDecoration(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-8).dp, y = (-12).dp),
                isPink = false,
                rotation = -12f
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // 3. Chunky Brutalist Progress Bar
        Box(modifier = Modifier.fillMaxWidth()) {
            BrutalCard(
                backgroundColor = CardPink,
                rotation = 0.8f,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "OVERALL PROGRESS",
                            fontSize = 13.sp,
                            fontFamily = GoogleSansFamily,
                            color = TextPrimary
                        )
                        Text(
                            text = "%",
                            fontSize = 20.sp,
                            fontFamily = CherryBombOneFamily,
                            color = TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // The bar track & fill
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(Color.White)
                            .border(2.5.dp, BrutalBorder, RoundedCornerShape(11.dp))
                    ) {
                        val fillFraction = (animatedPercent / 100f).coerceIn(0.01f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fillFraction)
                                .clip(RoundedCornerShape(11.dp))
                                .background(CardGreen)
                                .border(1.5.dp, BrutalBorder, RoundedCornerShape(11.dp))
                        )
                    }
                }
            }

            // Smiley sticker pinned on progress card
            SmileySticker(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 10.dp, y = 18.dp),
                rotation = 8f
            )
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}
