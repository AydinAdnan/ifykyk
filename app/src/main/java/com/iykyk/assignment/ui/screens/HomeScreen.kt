package com.iykyk.assignment.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iykyk.assignment.ui.components.*
import com.iykyk.assignment.ui.theme.*

@Composable
fun HomeScreen(
    onVideoSelected: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            if (uri != null) {
                onVideoSelected(uri)
            }
        }
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ScrapbookCanvas)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(36.dp))

        // 1. Top Yellow Card: "UNIQUE PERSON COLLAGE" in Cherry Bomb One
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            BrutalCard(
                backgroundColor = CardYellow,
                rotation = -1.5f,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "UNIQUE\nPERSON\nCOLLAGE",
                    fontSize = 34.sp,
                    fontFamily = CherryBombOneFamily,
                    lineHeight = 38.sp,
                    color = TextPrimary
                )
            }

            // Washi tape in top-left
            WashiTapeDecoration(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-8).dp, y = (-12).dp),
                isPink = false,
                rotation = -15f
            )

            // Bandaid in top-right
            BandaidDecoration(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-10).dp),
                rotation = 20f
            )

            // Sparkle doodle on the right
            ImageDoodleSparkle(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (-16).dp, y = (-20).dp)
            )

            // Heart doodle
            HeartDoodle(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-16).dp, y = (-12).dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 2. Purple Note: "Video in. People out. Collage made."
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            BrutalCard(
                backgroundColor = CardPurple,
                rotation = 1.8f,
                modifier = Modifier.fillMaxWidth(0.92f)
            ) {
                Text(
                    text = "Video in. People out. Collage made. \uD83D\uDE42",
                    fontSize = 15.sp,
                    fontFamily = GoogleSansFamily,
                    color = TextPrimary
                )
            }

            // Little tape strip on purple note
            WashiTapeDecoration(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-12).dp, y = (-8).dp),
                isPink = false,
                rotation = -8f
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // 3. Blue Torn Notepad Card: "SELECT VIDEO"
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .rotate(-0.8f)
            ) {
                // Hard Shadow
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .offset(x = 5.dp, y = 5.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(BrutalBorder)
                )

                // Blue Container
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(CardBlue)
                        .border(2.5.dp, BrutalBorder, RoundedCornerShape(16.dp))
                        .clickable {
                            videoPickerLauncher.launch("video/*")
                        }
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Inner dashed box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFE0F2FE))
                            .border(2.dp, BrutalBorder.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(CardPurple)
                                    .border(2.dp, BrutalBorder, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Videocam,
                                    contentDescription = "Video",
                                    tint = Color(0xFF6B21A8),
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "SELECT VIDEO",
                                fontSize = 18.sp,
                                fontFamily = CherryBombOneFamily,
                                color = TextPrimary
                            )
                            Text(
                                text = "MP4, MOV up to 2GB",
                                fontSize = 13.sp,
                                fontFamily = GoogleSansFamily,
                                color = TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Green Button inside Blue Note
                    BrutalButton(
                        text = "BROWSE FILES",
                        onClick = {
                            videoPickerLauncher.launch("video/*")
                        },
                        backgroundColor = CardGreen,
                        trailingIcon = {
                            Icon(Icons.Default.ArrowOutward, contentDescription = null, tint = TextPrimary)
                        }
                    )
                }
            }

            // Paperclip clipping onto the blue note
            PaperclipDecoration(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-20).dp, y = (-24).dp),
                isPink = true,
                rotation = 5f
            )

            // Pink tape scrap in bottom-left
            WashiTapeDecoration(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-16).dp, y = 14.dp),
                isPink = true,
                rotation = 24f
            )
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
fun ImageDoodleSparkle(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(com.iykyk.assignment.R.drawable.asset_doodle_sparkle),
        contentDescription = "Sparkle Doodle",
        modifier = modifier.size(24.dp)
    )
}
