package com.iykyk.assignment.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.iykyk.assignment.ui.theme.BrutalBorder
import com.iykyk.assignment.ui.theme.BrutalShadow
import com.iykyk.assignment.ui.theme.CardWhite
import com.iykyk.assignment.ui.theme.TextPrimary
import kotlin.math.roundToInt

/**
 * Custom Compose Shape that carves out authentic perforated stamp notches (teeth)
 * along all 4 edges of a rectangular card.
 */
class StampPerforatedShape(
    private val notchRadius: Float = 12f,
    private val notchSpacing: Float = 24f
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val baseRect = Path().apply {
            addRect(Rect(0f, 0f, size.width, size.height))
        }
        
        val cutouts = Path()
        
        // Top & Bottom edges
        val hNotchCount = ((size.width - 2 * notchSpacing) / notchSpacing).roundToInt().coerceAtLeast(1)
        val hStep = size.width / (hNotchCount + 1)
        for (i in 1..hNotchCount) {
            val cx = i * hStep
            cutouts.addOval(Rect(cx - notchRadius, -notchRadius, cx + notchRadius, notchRadius))
            cutouts.addOval(Rect(cx - notchRadius, size.height - notchRadius, cx + notchRadius, size.height + notchRadius))
        }
        
        // Left & Right edges
        val vNotchCount = ((size.height - 2 * notchSpacing) / notchSpacing).roundToInt().coerceAtLeast(1)
        val vStep = size.height / (vNotchCount + 1)
        for (i in 1..vNotchCount) {
            val cy = i * vStep
            cutouts.addOval(Rect(-notchRadius, cy - notchRadius, notchRadius, cy + notchRadius))
            cutouts.addOval(Rect(size.width - notchRadius, cy - notchRadius, size.width + notchRadius, cy + notchRadius))
        }
        
        val finalPath = Path()
        finalPath.op(baseRect, cutouts, PathOperation.Difference)
        return Outline.Generic(finalPath)
    }
}

/**
 * Postage Stamp Face Card Composable
 * Displays a person's representative shot inside an authentic postage stamp frame.
 */
@Composable
fun StampCard(
    imageModel: Any?,
    modifier: Modifier = Modifier,
    backgroundColor: Color = CardWhite,
    rotation: Float = 0f,
    label: String? = null,
    badgeText: String? = null
) {
    val stampShape = StampPerforatedShape(notchRadius = 10f, notchSpacing = 22f)

    Box(
        modifier = modifier
            .rotate(rotation)
            .shadow(
                elevation = 6.dp,
                shape = stampShape,
                clip = false,
                ambientColor = BrutalShadow,
                spotColor = BrutalShadow
            )
            .clip(stampShape)
            .background(backgroundColor)
            .border(2.dp, BrutalBorder, stampShape)
            .padding(8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Photo Area inside stamp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFE2E8F0))
                    .border(1.dp, BrutalBorder.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            ) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = label ?: "Person Photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                badgeText?.let { badge ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .background(Color(0xFFFFE66D), RoundedCornerShape(4.dp))
                            .border(1.dp, BrutalBorder, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badge,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary
                        )
                    }
                }
            }

            label?.let { text ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = TextPrimary,
                    maxLines = 1
                )
            }
        }
    }
}
