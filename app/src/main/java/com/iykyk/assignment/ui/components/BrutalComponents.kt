package com.iykyk.assignment.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iykyk.assignment.R
import com.iykyk.assignment.ui.theme.*

@Composable
fun BrutalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = CardGreen,
    contentColor: Color = TextPrimary,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    trailingIcon: (@Composable () -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val offset by animateDpAsState(
        targetValue = if (isPressed || !enabled) 0.dp else 4.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pressOffset"
    )

    Box(
        modifier = modifier.heightIn(min = 54.dp)
    ) {
        // Hard Black Shadow behind
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = 4.dp, y = 4.dp)
                .clip(shape)
                .background(BrutalBorder)
        )

        // Front Top Button Surface
        Box(
            modifier = Modifier
                .offset(x = 4.dp - offset, y = 4.dp - offset)
                .fillMaxWidth()
                .clip(shape)
                .background(if (enabled) backgroundColor else Color(0xFFE4E4E4))
                .border(2.5.dp, BrutalBorder, shape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                )
                .padding(horizontal = 20.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = text,
                    color = if (enabled) contentColor else TextMuted,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                trailingIcon?.let {
                    Spacer(modifier = Modifier.width(8.dp))
                    it()
                }
            }
        }
    }
}

@Composable
fun BrutalCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = CardYellow,
    borderColor: Color = BrutalBorder,
    rotation: Float = 0f,
    shadowOffset: Dp = 5.dp,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier.rotate(rotation)
    ) {
        // Hard shadow
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = shadowOffset, y = shadowOffset)
                .clip(shape)
                .background(BrutalBorder)
        )

        // Main card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(backgroundColor)
                .border(2.5.dp, borderColor, shape)
                .padding(16.dp),
            content = content
        )
    }
}

@Composable
fun WashiTapeDecoration(
    modifier: Modifier = Modifier,
    isPink: Boolean = false,
    rotation: Float = 0f
) {
    Image(
        painter = painterResource(if (isPink) R.drawable.asset_washi_tape_pink else R.drawable.asset_washi_tape_yellow),
        contentDescription = "Washi Tape",
        modifier = modifier
            .rotate(rotation)
            .width(80.dp)
            .height(26.dp)
    )
}

@Composable
fun BandaidDecoration(
    modifier: Modifier = Modifier,
    rotation: Float = 0f
) {
    Image(
        painter = painterResource(R.drawable.asset_bandaid),
        contentDescription = "Bandaid",
        modifier = modifier
            .rotate(rotation)
            .width(70.dp)
            .height(24.dp)
    )
}

@Composable
fun PaperclipDecoration(
    modifier: Modifier = Modifier,
    isPink: Boolean = true,
    rotation: Float = 0f
) {
    Image(
        painter = painterResource(if (isPink) R.drawable.asset_paperclip_pink else R.drawable.asset_paperclip_metal),
        contentDescription = "Paperclip",
        modifier = modifier
            .rotate(rotation)
            .width(22.dp)
            .height(50.dp)
    )
}

@Composable
fun SmileySticker(
    modifier: Modifier = Modifier,
    rotation: Float = 0f
) {
    Image(
        painter = painterResource(R.drawable.asset_sticky_smiley),
        contentDescription = "Smiley Note",
        modifier = modifier
            .rotate(rotation)
            .size(50.dp)
    )
}

@Composable
fun HeartDoodle(
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(R.drawable.asset_doodle_heart),
        contentDescription = "Heart Doodle",
        modifier = modifier.size(24.dp)
    )
}
