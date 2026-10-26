package com.chayu.ksu.ui.component.bottombar

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chayu.ksu.Natives
import com.chayu.ksu.R
import com.chayu.ksu.ui.LocalMainPagerState
import com.chayu.ksu.ui.component.FloatingBottomBar
import com.chayu.ksu.ui.component.FloatingBottomBarItem
import com.chayu.ksu.ui.theme.LocalEnableFloatingBottomBar
import com.chayu.ksu.ui.theme.LocalEnableFloatingBottomBarBlur
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.basic.Badge as MiuixBadge
import top.yukonga.miuix.kmp.basic.BadgedBox as MiuixBadgedBox
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.Text as MiuixText

@Composable
fun BottomBarMaterial(
    backdrop: Backdrop,
    navigationBadge: NavigationBadgeState,
    modifier: Modifier = Modifier,
) {
    val fullFeatured = Natives.isFullFeatured()
    if (!fullFeatured) return

    val mainPagerState = LocalMainPagerState.current
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current

    if (!enableFloatingBottomBar) {
        val items = listOf(
            Triple(R.string.home, Icons.Filled.Home, Icons.Outlined.Home),
            Triple(R.string.superuser, Icons.Filled.Shield, Icons.Outlined.Shield),
            Triple(R.string.module, Icons.Filled.Extension, Icons.Outlined.Extension),
            Triple(R.string.settings, Icons.Filled.Settings, Icons.Outlined.Settings),
        )

        ShortNavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(
                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
            )
        ) {
            items.forEachIndexed { index, (label, selectedIcon, unselectedIcon) ->
                val selected = mainPagerState.selectedPage == index
                ShortNavigationBarItem(
                    selected = selected,
                    onClick = {
                        if (!selected) {
                            mainPagerState.animateToPage(index)
                        }
                    },
                    icon = {
                        NavigationIconWithBadge(
                            icon = if (selected) selectedIcon else unselectedIcon,
                            contentDescription = stringResource(label),
                            badge = badgeFor(index, navigationBadge),
                        )
                    },
                    label = {
                        Text(
                            stringResource(label),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
        return
    }

    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        .let { inset -> if (inset != 0.dp) 8.dp + inset else 28.dp }
    val destinations = BottomBarDestination.entries

    FloatingBottomBar(
        modifier = modifier.padding(start = 32.dp, end = 32.dp, bottom = bottomPadding),
        selectedIndex = mainPagerState.selectedPage,
        onSelected = { mainPagerState.animateToPage(it) },
        backdrop = backdrop,
        tabsCount = destinations.size,
        isBlurEnabled = enableFloatingBottomBarBlur,
    ) { activateTab ->
        destinations.forEachIndexed { index, destination ->
            FloatingBottomBarItem(
                selected = mainPagerState.selectedPage == index,
                onClick = { activateTab(index) },
                modifier = Modifier.defaultMinSize(minWidth = 84.dp),
            ) {
                val badge = navigationBadgeFor(index, navigationBadge, floating = true)
                if (badge != null) {
                    MiuixBadgedBox(badge = { badge() }) {
                        MiuixIcon(
                            imageVector = destination.materialIcon,
                            contentDescription = stringResource(destination.materialLabel),
                        )
                    }
                } else {
                    MiuixIcon(
                        imageVector = destination.materialIcon,
                        contentDescription = stringResource(destination.materialLabel),
                    )
                }
                MiuixText(
                    text = stringResource(destination.materialLabel),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}

@Composable
internal fun NavigationIconWithBadge(
    icon: ImageVector,
    contentDescription: String?,
    badge: NavBadge?,
) {
    if (badge != null) {
        BadgedBox(
            badge = {
                when (badge.tone) {
                    BadgeTone.Alert -> Badge {
                        Text(badge.count.toString())
                    }

                    BadgeTone.Accent -> Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(badge.count.toString())
                    }
                }
            }
        ) {
            Icon(icon, contentDescription)
        }
    } else {
        Icon(icon, contentDescription)
    }
}
