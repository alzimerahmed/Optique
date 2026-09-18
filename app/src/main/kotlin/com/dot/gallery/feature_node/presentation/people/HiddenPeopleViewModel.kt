package com.dot.gallery.feature_node.presentation.people

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.data.dao.PersonDao
import com.dot.gallery.cloud.data.entity.PersonEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder for the hidden-people management surface.
 *
 * Note: unlike [com.dot.gallery.feature_node.presentation.ignored.IgnoredState] this is
 * not [android.os.Parcelable] — [PersonEntity] is a Room entity, not Parcelable.
 */
@Immutable
data class HiddenPeopleState(
    val people: List<PersonEntity> = emptyList()
)

/**
 * Manage-hidden ViewModel. Reads [PersonDao] directly (KTD1): routing through
 * CloudRepository/providers would hide every local person whenever the face
 * models are absent, because [com.dot.gallery.cloud.local.LocalPeopleProvider.isAvailable]
 * is model-gated — manage-hidden must work in noML/model-deleted states (R11).
 */
@HiltViewModel
class HiddenPeopleViewModel @Inject constructor(
    private val personDao: PersonDao
) : ViewModel() {

    val hiddenPeople = personDao.getByProvider(ProviderType.LOCAL_PEOPLE)
        .map { list -> HiddenPeopleState(people = list.filter(PersonEntity::hidden)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), HiddenPeopleState())

    fun unhide(personId: String) {
        viewModelScope.launch {
            personDao.setHidden(personId, false)
        }
    }
}
