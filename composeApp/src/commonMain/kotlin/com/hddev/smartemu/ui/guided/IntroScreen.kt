package com.hddev.smartemu.ui.guided

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hddev.smartemu.ui.components.AppButton
import com.hddev.smartemu.ui.components.ButtonEmphasis
import kotlinx.coroutines.launch

private class IntroPage(
    val title: String,
    val text: String,
    val illustration: @Composable () -> Unit
)

private val introPages = listOf(
    IntroPage(
        title = "Welcome to PassportEmu",
        text = "Your phone becomes a virtual passport or ID card. Another phone scans the page and reads the chip.",
        illustration = { PassportEmuMascot() }
    ),
    IntroPage(
        title = "You'll need a second phone",
        text = "The app that reads passports runs on another phone. That phone does the scanning; this one is " +
            "the passport.",
        illustration = { TwoPhonesIllustration() }
    ),
    IntroPage(
        title = "Scan the page, then hold the phones together",
        text = "As with a real passport, the app first photographs the photo page, then reads the chip while " +
            "the phones touch back to back. We'll guide you.",
        illustration = { ScanPageIllustration() }
    ),
    IntroPage(
        title = "Hold still until you see a tick",
        text = "Reading takes a few seconds. Keep the phones together and this screen will tell you how it's " +
            "going, and what to do if something goes wrong.",
        illustration = { TapPhonesIllustration(mode = TapMode.DEMO) }
    )
)

/**
 * A few swipeable pages that explain what the app does and how a read goes, shown on first launch or from help.
 * [onFinished] is called when the user skips or reaches the end.
 */
@Composable
fun IntroScreen(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val pagerState = rememberPagerState { introPages.size }
    val scope = rememberCoroutineScope()
    val lastPage = pagerState.currentPage == introPages.lastIndex

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                // Keeps its space on the last page, so that nothing moves
                TextButton(onClick = onFinished, enabled = !lastPage, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (lastPage) "" else "Skip")
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { index ->
                val page = introPages[index]
                BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    // Small screens give the words room first, so the picture shrinks to fit
                    val illustrationHeight = maxHeight * if (index == 0) 0.32f else 0.4f
                    Column(
                        modifier = Modifier
                            .widthIn(max = 520.dp)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(modifier = Modifier.heightIn(max = illustrationHeight), contentAlignment = Alignment.Center) {
                            page.illustration()
                        }
                        Headline(title = page.title, text = page.text)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                PageDots(count = introPages.size, current = pagerState.currentPage)
                AnimatedContent(targetState = lastPage, label = "introButton", modifier = Modifier.widthIn(max = 520.dp)) { last ->
                    if (last) {
                        AppButton(
                            text = "Get started",
                            onClick = onFinished,
                            icon = Icons.Filled.Check,
                            emphasis = ButtonEmphasis.High,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                        )
                    } else {
                        AppButton(
                            text = "Next",
                            onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                            icon = Icons.AutoMirrored.Filled.ArrowForward,
                            emphasis = ButtonEmphasis.High,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * A dot per page, the current one stretched into a pill.
 */
@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics { contentDescription = "Page ${current + 1} of $count" }
    ) {
        repeat(count) { index ->
            val selected = index == current
            val width by animateDpAsState(if (selected) 24.dp else 8.dp, label = "dotWidth")
            val color by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                label = "dotColor"
            )
            Box(
                modifier = Modifier
                    .size(height = 8.dp, width = width)
                    .background(color, CircleShape)
            )
        }
    }
}
