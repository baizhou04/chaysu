package com.chayu.ksu.ui.component.bottombar

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.chayu.ksu.R

internal val BottomBarDestination.materialIcon: ImageVector
    get() = when (this) {
        BottomBarDestination.Home -> Icons.Rounded.Cottage
        BottomBarDestination.SuperUser -> Icons.Rounded.Security
        BottomBarDestination.Module -> Icons.Rounded.Extension
        BottomBarDestination.Setting -> Icons.Rounded.Settings
    }

internal val BottomBarDestination.materialLabel: Int
    get() = when (this) {
        BottomBarDestination.Home -> R.string.home
        BottomBarDestination.SuperUser -> R.string.superuser
        BottomBarDestination.Module -> R.string.module
        BottomBarDestination.Setting -> R.string.settings
    }
