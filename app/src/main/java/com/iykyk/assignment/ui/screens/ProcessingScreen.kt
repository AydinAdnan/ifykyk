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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iykyk.assignment.domain.model.PipelineProgress
import com.iykyk.assignment.domain.model.PipelineStep
import com.iykyk.assignment.ui.components.*
import com.iykyk.assignment.ui.theme.*

@Composable
fun ProcessingScreen(
    progress: PipelineProgress,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GridPaperLavender)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // 1. Top Badge: "PROCESSING" with paperclip
        Box(
            modifier = Modifier.wrapContentSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF93C5FD))
                    .border(2.dp, BrutalBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "PROCESSING",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = TextPrimary
                )
            }

            PaperclipDecoration(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 16.dp, y = (-8).dp),
                isPink = false,
                rotation = 2f
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 2. White Center Card with Face Ring Progress
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            BrutalCard(
                backgroundColor = CardWhite,
                rotation = -0.5f,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Face Avatar inside Progress Ring
                    Box(
                        modifier = Modifier.size(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Background animated circular progress ring
                        val animatedProgress by animateFloatAsState(
                            targetValue = progress.progressPercent / 100f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessLow
                            ),
                            label = "ringProgress"
                        )

                        CircularProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.size(140.dp),
                            color = Color(0xFF8B5CF6),
                            strokeWidth = 6.dp,
                            trackColor = Color(0xFFE9D5FF)
                        )

                        // Inner Face Crop
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEDE9FE))
                                .border(2.dp, BrutalBorder, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            val faceBmp = progress.currentFaceBitmap
                            if (faceBmp != null) {
                                Image(
                                    bitmap = faceBmp.asImageBitmap(),
                                    contentDescription = "Current Face",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text(
                                    text = "\uD83D\uDC64",
                                    fontSize = 40.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Percentage Text
                    Text(
                        text = "%",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF6B21A8)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Status Message
                    Text(
                        text = progress.statusMessage,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 3. Pink Checklist Card
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            BrutalCard(
                backgroundColor = CardPink,
                rotation = 0.8f,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    PipelineStep.values().forEach { step ->
                        val isDone = progress.completedSteps.contains(step)
                        val isCurrent = progress.currentStep == step && !isDone

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            // Checkbox icon
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isDone) Color(0xFF22C55E) else if (isCurrent) CardYellow else Color.White)
                                    .border(1.5.dp, BrutalBorder, RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isDone) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Done",
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                } else if (isCurrent) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF6B21A8))
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = step.title,
                                fontSize = 14.sp,
                                fontWeight = if (isDone || isCurrent) FontWeight.Black else FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                                color = if (isDone || isCurrent) TextPrimary else TextMuted
                            )
                        }
                    }
                }
            }

            // Smiley sticker pinned to the bottom right of the checklist card
            SmileySticker(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 12.dp, y = 20.dp),
                rotation = 8f
            )
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}
