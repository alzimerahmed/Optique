/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.dot.gallery.R
import com.dot.gallery.feature_node.presentation.mediaview.rememberedDerivedState
import com.dot.gallery.ui.theme.ComponentSize
import com.dot.gallery.ui.theme.Spacing

@Composable
fun CheckBox(
    modifier: Modifier = Modifier,
    isChecked: Boolean,
    number: Int? = null,
    onCheck: (() -> Unit)? = null
) {
    val image = if (isChecked) Icons.Filled.CheckCircle else Icons.Outlined.Circle
    val color = if (isChecked) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurface
    if (onCheck != null) {
        val selectAllDescription = stringResource(R.string.select_all)
        Box(
            modifier = Modifier
                .sizeIn(
                    minWidth = ComponentSize.MinimumTouchTarget,
                    minHeight = ComponentSize.MinimumTouchTarget
                )
                .toggleable(
                    value = isChecked,
                    role = Role.Checkbox,
                    onValueChange = { onCheck() }
                )
                .semantics { contentDescription = selectAllDescription },
            contentAlignment = Alignment.Center
        ) {
            Image(
                imageVector = image,
                colorFilter = ColorFilter.tint(color),
                modifier = modifier,
                contentDescription = null
            )
        }
    } else {
        if (number != null) {
            val sizeModifier by rememberedDerivedState(number) {
                if (number > 99) {
                    Modifier.padding(horizontal = Spacing.ExtraSmall)
                } else Modifier
            }
            Text(
                text = "$number",
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(end = Spacing.ExtraSmall)
                    .defaultMinSize(Spacing.Large, Spacing.Large)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(100)
                    )
                    .then(sizeModifier)
            )
        } else {
            Image(
                imageVector = image,
                colorFilter = ColorFilter.tint(color),
                modifier = modifier,
                contentDescription = null
            )
        }
    }
}