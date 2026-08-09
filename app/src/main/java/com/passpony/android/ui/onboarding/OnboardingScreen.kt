package com.passpony.android.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.passpony.android.R
import com.passpony.android.ui.AppViewModel
import kotlinx.coroutines.launch

/**
 * Port of PassPony iOS's OnboardingView: the swipeable seven-slide
 * pager, Skip top-right, custom page dots, and a Next/Get Started
 * button that both advances the pager and (on the last page) completes
 * the tour. Both Skip and Get Started funnel into the same [onComplete]
 * callback, matching iOS -- there is no separate skip-vs-finish path,
 * only "did the tour end" (the packet's own exit criteria: "Skip at any
 * slide lands in a working app").
 *
 * [HorizontalPager]'s [rememberPagerState] is itself rememberSaveable
 * internally, so the current page survives even where the language
 * slide still does trigger a real Activity.recreate() (API 26-32's
 * AppCompat compat shim; API 33+ recomposes in place instead, per
 * MainActivity's configChanges="locale", so there's nothing to survive
 * there at all) -- no separate .id()-based re-key trick is needed the
 * way iOS uses one.
 */
@Composable
fun OnboardingScreen(appViewModel: AppViewModel, onComplete: () -> Unit) {
    // The store isn't opened yet at this point -- StoreListScreen (the
    // normal opener, via its own identical LaunchedEffect) hasn't
    // composed, since onboarding gates ahead of the whole nav graph.
    // Without this, the import/try-pass slides' appViewModel calls would
    // silently no-op against a null store (see AppViewModel.saveEntry).
    LaunchedEffect(Unit) { appViewModel.openStore() }

    val slides = remember { onboardingSlides() }
    val pagerState = rememberPagerState(pageCount = { slides.size })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == slides.lastIndex

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (!isLastPage) {
                    TextButton(onClick = onComplete) {
                        Text(stringResource(R.string.xc_skip))
                    }
                } else {
                    Spacer(Modifier.height(48.dp))
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { page ->
                OnboardingPage(
                    slide = slides[page],
                    appViewModel = appViewModel,
                    onAdvance = {
                        scope.launch {
                            val next = (page + 1).coerceAtMost(slides.lastIndex)
                            pagerState.animateScrollToPage(next)
                        }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                PageIndicator(count = slides.size, current = pagerState.currentPage)
                Button(onClick = {
                    if (isLastPage) {
                        onComplete()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                }) {
                    Text(stringResource(if (isLastPage) R.string.xc_get_started else R.string.xc_next))
                }
            }
        }
    }
}

@Composable
private fun PageIndicator(count: Int, current: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until count) {
            val selected = i == current
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(if (selected) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
                    )
            )
        }
    }
}
