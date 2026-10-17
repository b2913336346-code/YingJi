package com.yingji.app.ui.common

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

val NavigationCornerRadius = 16.dp

val TopNavigationBarShape = RoundedCornerShape(
    topStart = 0.dp,
    topEnd = 0.dp,
    bottomStart = NavigationCornerRadius,
    bottomEnd = NavigationCornerRadius,
)

val BottomNavigationBarShape = RoundedCornerShape(
    topStart = NavigationCornerRadius,
    topEnd = NavigationCornerRadius,
    bottomStart = 0.dp,
    bottomEnd = 0.dp,
)
