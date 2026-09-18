/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.setup.pages

import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dot.gallery.R
import com.dot.gallery.core.LocalEventHandler
import com.dot.gallery.core.Settings
import com.dot.gallery.core.Settings.Misc.rememberAppNameAlias
import com.dot.gallery.core.navigate
import com.dot.gallery.core.presentation.components.SetupButton
import com.dot.gallery.feature_node.presentation.setup.components.SetupSectionCard
import com.dot.gallery.feature_node.presentation.setup.components.SetupWizardScaffold
import com.dot.gallery.feature_node.presentation.util.Screen
import com.google.accompanist.drawablepainter.rememberDrawablePainter

@Composable
fun SetupLooksFeelPage(
    stepNumber: Int,
    totalSteps: Int,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val handler = LocalEventHandler.current
    var appNameAlias by rememberAppNameAlias()

    SetupWizardScaffold(
        showBack = true,
        onBack = onBack,
        stepNumber = stepNumber,
        totalSteps = totalSteps,
        title = stringResource(R.string.setup_looks_title),
        subtitle = stringResource(R.string.setup_looks_subtitle),
        bottomBar = {
            SetupButton(
                text = stringResource(R.string.setup_continue),
                applyHorizontalPadding = false,
                applyBottomPadding = false,
                applyInsets = false,
                applyNavigationPadding = false,
                onClick = onNext
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // App name
            SetupSectionCard(title = stringResource(R.string.setup_section_app_name)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf(Settings.Misc.ALIAS_REFRA, Settings.Misc.ALIAS_GALLERY).forEach { alias ->
                        LogoChoice(
                            modifier = Modifier.weight(1f),
                            label = alias,
                            iconRes = R.mipmap.ic_launcher_round,
                            selected = appNameAlias == alias,
                            onClick = { appNameAlias = alias }
                        )
                    }
                }
            }

            // Appearance
            SetupSectionCard(
                title = stringResource(R.string.setup_section_appearance),
                subtitle = stringResource(R.string.setup_appearance_summary)
            ) {
                SetupButton(
                    text = stringResource(R.string.setup_open_appearance),
                    applyHorizontalPadding = false,
                    applyBottomPadding = false,
                    applyInsets = false,
                    applyNavigationPadding = false,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = { handler.navigate(Screen.SettingsAppearanceScreen()) }
                )
            }
        }
    }
}

@Composable
private fun LogoChoice(
    label: String,
    iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    else Color.Transparent
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(16.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Image(
            painter = rememberDrawablePainter(
                drawable = AppCompatResources.getDrawable(context, iconRes)
            ),
            contentDescription = label,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}
