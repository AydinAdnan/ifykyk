package com.iykyk.assignment.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iykyk.assignment.domain.model.AnalysisResult
import com.iykyk.assignment.ui.components.*
import com.iykyk.assignment.ui.theme.*

@Composable
fun CollageSavedScreen(
    result: AnalysisResult,
    onShare: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ScrapbookCanvas)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(28.dp))

        // 1. Green Header: "COLLAGE SAVED! ✨" in Cherry Bomb One
        Box(modifier = Modifier.fillMaxWidth()) {
            BrutalCard(
                backgroundColor = CardGreen,
                rotation = -1f,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "COLLAGE SAVED! ✨",
                        fontSize = 24.sp,
                        fontFamily = CherryBombOneFamily,
                        color = TextPrimary
                    )
                }
            }

            WashiTapeDecoration(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-8).dp, y = (-10).dp),
                isPink = false,
                rotation = -14f
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 2. High-Res Collage Preview Poster
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .aspectRatio(9f / 14.5f)
                    .shadow(8.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .border(2.5.dp, BrutalBorder, RoundedCornerShape(12.dp))
                    .padding(6.dp)
            ) {
                val collageBmp = result.collageBitmap
                if (collageBmp != null) {
                    Image(
                        bitmap = collageBmp.asImageBitmap(),
                        contentDescription = "Saved Collage",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Pink washi tape holding the top of the photo
            WashiTapeDecoration(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-10).dp),
                isPink = true,
                rotation = -3f
            )

            // Heart doodle in bottom-right corner
            HeartDoodle(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 10.dp, y = (-20).dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 3. Blue Note: "Your collage has been saved to your gallery."
        Box(modifier = Modifier.fillMaxWidth()) {
            BrutalCard(
                backgroundColor = CardBlue,
                rotation = 0.5f,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Your collage has been saved to your gallery. \uD83D\uDE42",
                    fontSize = 15.sp,
                    fontFamily = GoogleSansFamily,
                    color = TextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 4. Yellow Action Button: SHARE COLLAGE
        Box(modifier = Modifier.fillMaxWidth()) {
            BrutalButton(
                text = "SHARE COLLAGE",
                onClick = onShare,
                backgroundColor = CardYellow,
                trailingIcon = {
                    Icon(Icons.Default.Share, contentDescription = null, tint = TextPrimary)
                }
            )

            // Paperclip on top-right of share button
            PaperclipDecoration(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 8.dp, y = (-18).dp),
                isPink = false,
                rotation = 12f
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 5. Back Home Button
        BrutalButton(
            text = "ANALYZE ANOTHER VIDEO",
            onClick = onHome,
            backgroundColor = CardWhite,
            trailingIcon = {
                Icon(Icons.Default.Home, contentDescription = null, tint = TextPrimary)
            }
        )

        Spacer(modifier = Modifier.height(48.dp))
    }
}
