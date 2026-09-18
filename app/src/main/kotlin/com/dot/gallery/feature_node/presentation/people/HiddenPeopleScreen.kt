package com.dot.gallery.feature_node.presentation.people

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.dot.gallery.R
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.data.entity.PersonEntity
import com.dot.gallery.core.presentation.components.NavigationBackButton
import com.dot.gallery.feature_node.presentation.settings.components.SettingsItem
import com.dot.gallery.feature_node.presentation.settings.components.settings
import com.dot.gallery.feature_node.presentation.util.PreviewHost

@Composable
fun HiddenPeopleScreen() {
    val vm = hiltViewModel<HiddenPeopleViewModel>()
    val state by vm.hiddenPeople.collectAsStateWithLifecycle()

    HiddenPeopleContent(
        state = state,
        onUnhide = vm::unhide
    )
}

@SuppressLint("StringFormatInvalid")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenPeopleContent(
    state: HiddenPeopleState,
    onUnhide: (String) -> Unit
) {
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val resources = LocalResources.current
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.hidden_people_title),
                    )
                },
                navigationIcon = {
                    NavigationBackButton()
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                if (state.people.isEmpty()) {
                    item {
                        NoHiddenPeople()
                    }
                    item {
                        Text(
                            modifier = Modifier
                                .padding(24.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    shape = RoundedCornerShape(24.dp)
                                )
                                .padding(16.dp),
                            text = stringResource(R.string.hidden_people_explainer),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    item {
                        Spacer(Modifier.height(24.dp))
                    }

                    settings(
                        preferenceItemBuilder = { item, modifier ->
                            val unhideContentDescription =
                                stringResource(R.string.hidden_people_unhide, item.title)
                            SettingsItem(
                                item = item,
                                modifier = modifier.semantics {
                                    contentDescription = unhideContentDescription
                                },
                                customIcon = { _, iconUri, _ ->
                                    HiddenPersonThumbnail(thumbnailUrl = iconUri)
                                }
                            )
                        }
                    ) {
                        state.people.forEach { person ->
                            Preference(
                                title = person.name.ifBlank {
                                    resources.getString(R.string.cloud_people_unknown)
                                },
                                // String-typed icon maps to SettingsEntity.Preference.iconUri.
                                // orEmpty() keeps the icon slot present so the person-icon
                                // placeholder renders when there is no thumbnail.
                                icon = person.thumbnailUrl.orEmpty(),
                                summary = resources.getString(
                                    R.string.cloud_person_photo_count,
                                    person.faceCount
                                ),
                                tag = person.id,
                                onClick = {
                                    onUnhide(person.id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Circular face thumbnail, or the PersonGridItem-style placeholder when none is stored. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HiddenPersonThumbnail(thumbnailUrl: String?) {
    if (thumbnailUrl.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        GlideImage(
            model = thumbnailUrl.toUri(),
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
fun NoHiddenPeople(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 16.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Icon(
            modifier = Modifier.size(124.dp),
            imageVector = Icons.Outlined.VisibilityOff,
            tint = MaterialTheme.colorScheme.primary,
            contentDescription = null
        )

        Text(
            text = stringResource(R.string.hidden_people_empty),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
    }
}

private fun previewPerson(
    id: String,
    name: String,
    faceCount: Int,
    thumbnailUrl: String? = null
) = PersonEntity(
    id = id,
    name = name,
    providerType = ProviderType.LOCAL_PEOPLE,
    thumbnailUrl = thumbnailUrl,
    faceCount = faceCount,
    hidden = true
)

@Preview(showBackground = true)
@Composable
private fun HiddenPeopleContentEmptyPreview() {
    PreviewHost {
        HiddenPeopleContent(
            state = HiddenPeopleState(),
            onUnhide = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HiddenPeopleContentWithPeoplePreview() {
    PreviewHost {
        HiddenPeopleContent(
            state = HiddenPeopleState(
                people = listOf(
                    previewPerson("local_1", "Alice", 12, "/tmp/face_1.jpg"),
                    previewPerson("local_2", "", 3),
                    previewPerson("local_3", "Carol", 27, "/tmp/face_3.jpg")
                )
            ),
            onUnhide = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NoHiddenPeoplePreview() {
    PreviewHost {
        NoHiddenPeople()
    }
}
