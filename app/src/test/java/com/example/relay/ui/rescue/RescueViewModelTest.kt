package com.example.relay.ui.rescue

import com.example.relay.rescue.CourierRescueItem
import com.example.relay.rescue.InMemoryRescueEnvelopeRepository
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.RescueSupportNeed
import com.example.relay.rescue.ShelterPublicKeyProvider
import com.example.relay.rescue.ShelterPublicKeys
import com.example.relay.location.FixedLocationProvider
import com.example.relay.location.GeoFix
import com.example.relay.rescue.RescueCondition
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.session.testSessionCoordinator
import java.lang.reflect.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RescueViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `request creation keeps SOS safely queued when shelter public key is unavailable`() = runBlocking {
        val repository = InMemoryRescueEnvelopeRepository()
        val viewModel = RescueViewModel(
            coordinator = testSessionCoordinator(
                repository,
                ShelterPublicKeyProvider { null },
                nowEpochMillis = { TEST_NOW },
            ),
            repository = repository,
            nowEpochMillis = { TEST_NOW },
        )
        viewModel.onNavigate(RescueScreen.REQUEST_FORM)
        viewModel.onDraftChange(
            requireNotNull(viewModel.state.value.draft).copy(
                destinationShelterId = "shelter-1",
                personCount = 1,
                conditions = setOf(RescueCondition.INJURED_OR_UNWELL),
                freeText = PRIVATE_NOTE,
            ),
        )

        viewModel.onSubmitRequest()

        val queued = withTimeout(ASYNC_TIMEOUT_MILLIS) {
            viewModel.state.first { !it.isRequestSubmitting && it.screen == RescueScreen.BROADCASTING }
        }
        assertEquals(RescueScreen.BROADCASTING, queued.screen)
        assertEquals(RescueSubmissionStatus.PENDING_DESTINATION, queued.ownRequest!!.submissionStatus)
        assertTrue(queued.broadcast.isActive)
        assertTrue(queued.courierAutomation.isEnabled)
        assertTrue(repository.all().isEmpty())
    }

    @Test
    fun `configured shelter key encrypts request before storage and enables automatic transport`() = runBlocking {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val repository = InMemoryRescueEnvelopeRepository()
        val keys = ShelterPublicKeys("shelter-1", recipient.publicKey, signer.publicKey)
        val viewModel = RescueViewModel(
            coordinator = testSessionCoordinator(
                repository,
                ShelterPublicKeyProvider { keys },
                nowEpochMillis = { TEST_NOW },
            ),
            repository = repository,
            nowEpochMillis = { TEST_NOW },
        )
        val draft = requireNotNull(viewModel.state.value.draft).copy(
            personCount = 3,
            injured = true,
            supportNeeds = setOf(RescueSupportNeed.WATER, RescueSupportNeed.MEDICINE),
            freeText = PRIVATE_NOTE,
        )
        viewModel.onNavigate(RescueScreen.REQUEST_FORM)
        viewModel.onDraftChange(draft)

        viewModel.onSubmitRequest()

        val broadcasting = withTimeout(ASYNC_TIMEOUT_MILLIS) {
            viewModel.state.first { it.screen == RescueScreen.BROADCASTING && !it.isRequestSubmitting }
        }
        val stored = repository.all().single()
        val decrypted = RescueCryptography.decrypt(stored.envelope, recipient.privateKey)
        assertTrue(broadcasting.broadcast.isActive)
        assertTrue(broadcasting.courierAutomation.isEnabled)
        assertTrue(broadcasting.broadcast.statusMessage.isNotBlank())
        assertEquals(3, decrypted.personCount)
        assertEquals(PRIVATE_NOTE, decrypted.freeText)
        assertNotEquals(PRIVATE_NOTE, stored.envelope.ciphertextBase64)
        assertFalse(stored.envelope.toString().contains(PRIVATE_NOTE))
        assertTrue(repository.recordSuccessfulExport(stored.key, 1))
        viewModel.onRefreshStatus()
        val refreshed = withTimeout(ASYNC_TIMEOUT_MILLIS) {
            viewModel.state.first { it.broadcast.transferCount == 1 }
        }
        assertEquals(RescueSubmissionStatus.IN_TRANSIT, refreshed.ownRequest!!.submissionStatus)
    }

    @Test
    fun `two second SOS action stores life threat with unknown count and GPS`() = runBlocking {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val repository = InMemoryRescueEnvelopeRepository()
        val keys = ShelterPublicKeys("regional-area", recipient.publicKey, signer.publicKey)
        val viewModel = RescueViewModel(
            coordinator = testSessionCoordinator(
                repository,
                ShelterPublicKeyProvider { keys },
                locationProvider = FixedLocationProvider(GeoFix(0.0, 0.0, 7f, TEST_NOW)),
                nowEpochMillis = { TEST_NOW },
            ),
            repository = repository,
            nowEpochMillis = { TEST_NOW },
        )

        viewModel.onSendSos()

        withTimeout(ASYNC_TIMEOUT_MILLIS) {
            viewModel.state.first { it.screen == RescueScreen.BROADCASTING && !it.isRequestSubmitting }
        }
        val payload = RescueCryptography.decrypt(repository.all().single().envelope, recipient.privateKey)
        assertEquals(0, payload.personCount)
        assertEquals(RescueUrgency.IMMEDIATE, payload.urgency)
        assertEquals(setOf(RescueCondition.LIFE_THREATENING), payload.conditions)
        assertEquals(0.0, payload.location!!.latitude!!, 0.0)
        assertEquals(TEST_NOW, payload.location!!.capturedAtEpochMillis)
    }

    @Test
    fun `restored request update is committed through the coordinator without ViewModel version allocation`() = runBlocking {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val repository = InMemoryRescueEnvelopeRepository()
        val coordinator = testSessionCoordinator(
            repository,
            ShelterPublicKeyProvider { ShelterPublicKeys("shelter-1", recipient.publicKey, signer.publicKey) },
            nowEpochMillis = { TEST_NOW },
        )
        val first = RescueViewModel(coordinator, repository, nowEpochMillis = { TEST_NOW })
        first.onNavigate(RescueScreen.REQUEST_FORM)
        first.onDraftChange(
            requireNotNull(first.state.value.draft).copy(
                personCount = 1,
                conditions = setOf(RescueCondition.INJURED_OR_UNWELL),
                freeText = PRIVATE_NOTE,
            ),
        )
        first.onSubmitRequest()
        withTimeout(ASYNC_TIMEOUT_MILLIS) { first.state.first { it.ownRequest?.requestVersion == 1 } }

        // Simulates Activity/ViewModel recreation: only coordinator/store state survives.
        val recreated = RescueViewModel(coordinator, repository, nowEpochMillis = { TEST_NOW + 1_000 })
        val restored = withTimeout(ASYNC_TIMEOUT_MILLIS) {
            recreated.state.first { it.ownRequest?.requestVersion == 1 }
        }
        recreated.onPrepareUpdate()
        val edit = withTimeout(ASYNC_TIMEOUT_MILLIS) {
            recreated.state.first { it.screen == RescueScreen.REQUEST_FORM && it.draft?.requestId == restored.ownRequest!!.requestId }
        }
        // The form retains the durable version; only the coordinator reserves version 2.
        assertEquals(1, edit.draft!!.requestVersion)
        recreated.onDraftChange(edit.draft.copy(personCount = 2, freeText = "updated private details"))
        recreated.onSubmitRequest()

        withTimeout(ASYNC_TIMEOUT_MILLIS) { recreated.state.first { it.ownRequest?.requestVersion == 2 } }
        val stored = repository.all().single()
        assertEquals(2, stored.envelope.requestVersion)
        assertEquals(2, RescueCryptography.decrypt(stored.envelope, recipient.privateKey).personCount)
    }

    @Test
    fun `rescue language starts in Japanese and toggles without changing the draft`() {
        val repository = InMemoryRescueEnvelopeRepository()
        val viewModel = RescueViewModel(
            coordinator = testSessionCoordinator(
                repository,
                ShelterPublicKeyProvider { null },
                nowEpochMillis = { TEST_NOW },
            ),
            repository = repository,
            nowEpochMillis = { TEST_NOW },
        )
        val draftId = requireNotNull(viewModel.state.value.draft).requestId

        assertEquals(RescueLanguage.JAPANESE, viewModel.state.value.language)
        viewModel.onToggleLanguage()
        assertEquals(RescueLanguage.ENGLISH, viewModel.state.value.language)
        assertEquals(draftId, viewModel.state.value.draft?.requestId)
        viewModel.onToggleLanguage()
        assertEquals(RescueLanguage.JAPANESE, viewModel.state.value.language)
    }

    @Test
    fun `courier state exposes only delivery metadata and never rescue plaintext`() = runBlocking {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val repository = InMemoryRescueEnvelopeRepository()
        val keys = ShelterPublicKeys("shelter-1", recipient.publicKey, signer.publicKey)
        val memberViewModel = RescueViewModel(
            coordinator = testSessionCoordinator(
                repository,
                ShelterPublicKeyProvider { keys },
                nowEpochMillis = { TEST_NOW },
            ),
            repository = repository,
            nowEpochMillis = { TEST_NOW },
        )
        memberViewModel.onDraftChange(
            requireNotNull(memberViewModel.state.value.draft).copy(
                injured = true,
                personCount = 4,
                freeText = PRIVATE_NOTE,
            ),
        )
        memberViewModel.onSubmitRequest()
        withTimeout(ASYNC_TIMEOUT_MILLIS) {
            memberViewModel.state.first { it.screen == RescueScreen.BROADCASTING }
        }

        val courierViewModel = RescueViewModel(
            coordinator = testSessionCoordinator(
                repository,
                ShelterPublicKeyProvider { null },
                nowEpochMillis = { TEST_NOW + 1_000 },
            ),
            repository = repository,
            nowEpochMillis = { TEST_NOW + 1_000 },
        )
        courierViewModel.onNavigate(RescueScreen.COURIER_INVENTORY)

        val item = withTimeout(ASYNC_TIMEOUT_MILLIS) {
            courierViewModel.state.first { it.courierItems.size == 1 }.courierItems.single()
        }
        assertEquals("shelter-1", item.destinationShelterId)
        assertEquals(RescueSubmissionStatus.PENDING, item.submissionStatus)
        assertFalse(item.toString().contains(PRIVATE_NOTE))
        assertFalse(item.toString().contains("member-device"))
        assertEquals(COURIER_METADATA_FIELDS, courierVisibleFields())
    }

    private fun courierVisibleFields(): Set<String> = CourierRescueItem::class.java.declaredFields
        .asSequence()
        .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
        .map { it.name }
        .toSet()

    private companion object {
        const val TEST_NOW = 1_700_000_000_000L
        const val ASYNC_TIMEOUT_MILLIS = 10_000L
        const val PRIVATE_NOTE = "玄関奥に重傷者。位置情報を含む秘密メモ"
        val COURIER_METADATA_FIELDS = setOf(
            "requestId",
            "requestVersion",
            "destinationShelterId",
            "receivedAtEpochMillis",
            "expiresAtEpochMillis",
            "submissionStatus",
            "submissionCount",
        )
    }
}
