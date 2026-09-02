package com.iykyk.assignment.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iykyk.assignment.domain.model.AnalysisResult
import com.iykyk.assignment.ui.components.*
import com.iykyk.assignment.ui.theme.*

@Composable
fun CollagePreviewScreen(
    result: AnalysisResult,
    onSaveToGallery: (Bitmap) -> Unit,
    onShareCollage: (Bitmap) -> Unit,
    onViewBreakdown: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ScrapbookCanvas)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(28.dp))

        // 1. Top Badge: "COLLAGE PREVIEW" in Cherry Bomb One
        Box(
            modifier = Modifier.wrapContentSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardPink)
                    .border(2.5.dp, BrutalBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 28.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "COLLAGE PREVIEW",
                    fontSize = 24.sp,
                    fontFamily = CherryBombOneFamily,
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

        // 2. High-Res Canvas Collage Poster Preview
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .aspectRatio(9f / 14.5f)
                    .shadow(10.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .border(3.dp, BrutalBorder, RoundedCornerShape(14.dp))
                    .padding(6.dp)
            ) {
                val collageBmp = result.collageBitmap
                if (collageBmp != null) {
                    Image(
                        bitmap = collageBmp.asImageBitmap(),
                        contentDescription = "Collage Poster Preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Pink washi tape holding top of collage preview
            WashiTapeDecoration(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-10).dp),
                isPink = true,
                rotation = -3f
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 3. Action Buttons: SAVE TO GALLERY, SHARE COLLAGE, VIEW BREAKDOWN
        BrutalButton(
            text = "SAVE TO GALLERY",
            onClick = {
                result.collageBitmap?.let { bmp ->
                    onSaveToGallery(bmp)
                    Toast.makeText(context, "Collage saved to Photos / Gallery!", Toast.LENGTH_SHORT).show()
                }
            },
            backgroundColor = CardGreen,
            trailingIcon = {
                Icon(Icons.Default.Download, contentDescription = null, tint = TextPrimary)
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        BrutalButton(
            text = "SHARE COLLAGE",
            onClick = {
                result.collageBitmap?.let { bmp ->
                    onShareCollage(bmp)
                }
            },
            backgroundColor = CardYellow,
            trailingIcon = {
                Icon(Icons.Default.Share, contentDescription = null, tint = TextPrimary)
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        BrutalButton(
            text = "VIEW BREAKDOWN",
            onClick = onViewBreakdown,
            backgroundColor = CardWhite,
            trailingIcon = {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = TextPrimary)
            }
        )

        Spacer(modifier = Modifier.height(48.dp))
    }
}
