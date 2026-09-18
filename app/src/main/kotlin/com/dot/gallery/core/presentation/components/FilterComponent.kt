/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.HorizontalSplit
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.dot.gallery.R
import com.dot.gallery.core.Settings
import com.dot.gallery.core.Settings.Album.rememberLastSort
import com.dot.gallery.feature_node.domain.util.MediaOrder
import com.dot.gallery.feature_node.domain.util.OrderType
import com.dot.gallery.ui.theme.Spacing

@Composable
fun FilterButton(
    modifier: Modifier = Modifier,
    filterOptions: Array<FilterOption> = emptyArray(),
    viewType: Settings.Album.ViewType,
    onViewTypeChange: (Settings.Album.ViewType) -> Unit
) {
    var lastSort by rememberLastSort()
    var expanded by remember { mutableStateOf(false) }
    val selectedFilter by remember(lastSort) { mutableStateOf(filterOptions.first { it.filterKind == lastSort.kind }) }
    var order: OrderType by remember(lastSort) { mutableStateOf(lastSort.orderType) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.Medium)
            .padding(horizontal = Spacing.Small),
        contentAlignment = Alignment.TopEnd
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = {
                    if (viewType == Settings.Album.ViewType.GRID) {
                        onViewTypeChange(Settings.Album.ViewType.LIST)
                    } else {
                        onViewTypeChange(Settings.Album.ViewType.GRID)
                    }
                }
            ) {
                Icon(
                    imageVector = if (viewType == Settings.Album.ViewType.GRID) Icons.Outlined.GridView else Icons.Outlined.HorizontalSplit,
                    contentDescription = stringResource(R.string.toggle_view_type),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100))
                        .clickable(role = Role.Button) {
                            expanded = true
                        }
                        .padding(vertical = Spacing.ExtraSmall, horizontal = Spacing.Small),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    text = stringResource(selectedFilter.titleRes)
                )
                IconButton(
                    onClick = {
                        order = if (order == OrderType.Ascending) OrderType.Descending else OrderType.Ascending
                        lastSort = lastSort.copy(orderType = order)
                    }
                ) {
                    Icon(
                        imageVector = remember(selectedFilter, order) {
                            if (order == OrderType.Descending)
                                Icons.Outlined.KeyboardDoubleArrowDown
                            else Icons.Outlined.KeyboardDoubleArrowUp
                        },
                        tint = MaterialTheme.colorScheme.primary,
                        contentDescription = stringResource(
                            if (order == OrderType.Descending) R.string.sort_descending
                            else R.string.sort_ascending
                        )
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .wrapContentSize(Alignment.TopEnd)
        ) {
            DropdownMenu(
                modifier = Modifier.align(Alignment.TopEnd),
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                filterOptions.forEach { filter ->
                    DropdownMenuItem(
                        text = { Text(text = stringResource(filter.titleRes)) },
                        onClick = {
                            lastSort = lastSort.copy(kind = filter.filterKind)
                        },
                        trailingIcon = {
                            if (lastSort.kind == filter.filterKind) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}


data class FilterOption(
    val titleRes: Int = -1,
    val onClick: (MediaOrder) -> Unit = {},
    val filterKind: FilterKind = FilterKind.DATE
)

enum class FilterKind {
    DATE, DATE_MODIFIED, NAME
}