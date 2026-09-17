/*
 * SPDX-FileCopyrightText: 2023-2026 alzimerahmed
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dot.gallery.R
import com.dot.gallery.core.Settings
import com.dot.gallery.feature_node.domain.memories.OnThisDayGroup
import com.dot.gallery.feature_node.presentation.main.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional, default-off daily notification for on-this-day memories (U5, R7).
 *
 * Posts at most once per local day: the [Settings.Memories.NOTIFICATION_LAST_POSTED_DATE]
 * preference records the last post date and gates repeats. The path is fully local —
 * no network or ML involvement — and never crashes when permission is denied or the
 * preference store write fails.
 */
@Singleton
class MemoriesNotifier
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val clock: Clock,
        private val settingsStore: DataStore<Preferences>,
    ) {
        /**
         * Posts the on-this-day notification when every gate passes, in order:
         * a match exists ([onThisDayGroups] non-empty), [Manifest.permission.POST_NOTIFICATIONS]
         * is granted on API 33+, the user toggle is on, and no notification was posted
         * yet today. Returns true when it actually posted.
         */
        suspend fun maybePostToday(onThisDayGroups: List<OnThisDayGroup>): Boolean {
            if (!canPostToday(onThisDayGroups)) return false
            postNotification(onThisDayGroups.maxOf { it.year })
            runCatching {
                settingsStore.edit {
                    it[Settings.Memories.NOTIFICATION_LAST_POSTED_DATE] =
                        LocalDate.now(clock).toString()
                }
            }
            return true
        }

        private suspend fun canPostToday(onThisDayGroups: List<OnThisDayGroup>): Boolean {
            if (onThisDayGroups.isEmpty() || !hasPostPermission()) return false
            val prefs = settingsStore.data.first()
            return prefs[Settings.Memories.NOTIFICATION_ENABLED] == true &&
                prefs[Settings.Memories.NOTIFICATION_LAST_POSTED_DATE] !=
                LocalDate.now(clock).toString()
        }

        private fun ensureChannel(): String {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_MEMORIES) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_MEMORIES,
                        context.getString(R.string.memories_notification_channel),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description =
                            context.getString(R.string.memories_notification_channel_description)
                    },
                )
            }
            return CHANNEL_MEMORIES
        }

        private fun hasPostPermission(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED

        @SuppressLint("MissingPermission")
        private fun postNotification(year: Int) {
            val channelId = ensureChannel()
            // Explicit component: reaches MainActivity without a manifest intent-filter,
            // while the ACTION_VIEW data URI matches the navDeepLink on the
            // OnDeviceMemoriesScreen destination.
            val deepLinkIntent =
                Intent(
                    Intent.ACTION_VIEW,
                    "$DEEP_LINK_URI?year=$year".toUri(),
                    context,
                    MainActivity::class.java,
                )
            val contentIntent =
                PendingIntent.getActivity(
                    context,
                    REQUEST_CODE_MEMORIES,
                    deepLinkIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                NotificationCompat
                    .Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_launcher_foreground_monochrome)
                    .setContentTitle(context.getString(R.string.memories_notification_title))
                    .setContentText(context.getString(R.string.memories_notification_body))
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
            runCatching {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_MEMORIES, notification)
            }
        }

        private companion object {
            const val CHANNEL_MEMORIES = "memories_on_this_day"
            const val NOTIFICATION_ID_MEMORIES = 91004
            const val REQUEST_CODE_MEMORIES = 91004
            const val DEEP_LINK_URI = "app://optique/memories"
        }
    }
