package com.iykyk.assignment

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iykyk.assignment.ui.screens.*
import com.iykyk.assignment.ui.theme.MyApplicationTheme
import com.iykyk.assignment.ui.viewmodel.CollageViewModel
import com.iykyk.assignment.ui.viewmodel.ScreenState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val viewModel: CollageViewModel = viewModel()
                val screenState by viewModel.screenState.collectAsState()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AnimatedContent(
                        targetState = screenState,
                        contentKey = { it::class },
                        transitionSpec = {
                            fadeIn(animationSpec = tween(300)) + slideInVertically(
                                animationSpec = tween(350),
                                initialOffsetY = { 80 }
                            ) togetherWith fadeOut(animationSpec = tween(200))
                        },
                        label = "screenTransition",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) { state ->
                        when (state) {
                            is ScreenState.Home -> {
                                HomeScreen(
                                    onVideoSelected = { uri ->
                                        viewModel.onVideoSelected(uri)
                                    }
                                )
                            }
                            is ScreenState.Processing -> {
                                ProcessingScreen(
                                    progress = state.progress
                                )
                            }
                            is ScreenState.Results -> {
                                ResultsScreen(
                                    result = state.result,
                                    onViewCollage = {
                                        viewModel.navigateTo(ScreenState.CollagePreview(state.result))
                                    }
                                )
                            }
                            is ScreenState.CollagePreview -> {
                                CollagePreviewScreen(
                                    result = state.result,
                                    onSaveToGallery = { bitmap ->
                                        viewModel.saveAndShareCollage(bitmap) {
                                            // Saved to gallery
                                        }
                                    },
                                    onShareCollage = { bitmap ->
                                        viewModel.saveAndShareCollage(bitmap) {
                                            val shareIntent = viewModel.getShareIntent(bitmap)
                                            startActivity(shareIntent)
                                        }
                                    },
                                    onViewBreakdown = {
                                        viewModel.navigateTo(ScreenState.AppearanceBreakdown(state.result))
                                    }
                                )
                            }
                            is ScreenState.AppearanceBreakdown -> {
                                AppearanceBreakdownScreen(
                                    result = state.result,
                                    onBackToCollage = {
                                        viewModel.navigateTo(ScreenState.CollagePreview(state.result))
                                    }
                                )
                            }
                            is ScreenState.CollageSaved -> {
                                CollageSavedScreen(
                                    result = state.result,
                                    onShare = {
                                        state.result.collageBitmap?.let { bmp ->
                                            val shareIntent = viewModel.getShareIntent(bmp)
                                            startActivity(shareIntent)
                                        }
                                    },
                                    onHome = {
                                        viewModel.resetToHome()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
