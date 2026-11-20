package com.v2ray.ang.ui.sc

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Экран входа: опрос /tv/pair/poll, остановка при уходе с экрана и в onCleared, истечение кода,
 * вход по /redeem (мастер-ключ) и ошибки. Сеть — мок ScAccount, время — виртуальное (StandardTestDispatcher).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)   // без AngApplication: она грузит нативный MMKV
class ScActivationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var account: ScAccount

    private val pair = ScAccount.TvPair("pid", "secret", "8493-2017", "https://sibir-cloud-api.site/tv?c=84932017", 10, 3)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        account = mock()
        whenever(account.tvPairStart(any())).thenReturn(pair)
        whenever(account.tvPairPoll(any())).thenReturn(ScAccount.TvPoll.Pending)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = ScActivationViewModel(
        ApplicationProvider.getApplicationContext<Application>(), account, dispatcher,
    ) { dispatcher.scheduler.currentTime }

    @Test
    fun initialState_isLoadingAndEmpty() {
        val st = vm().state.value
        assertEquals(PairPhase.Loading, st.pair)
        assertEquals("", st.code)
        assertFalse(st.busy)
        assertNull(st.error)
        assertNull(st.activatedLink)
    }

    @Test
    fun resume_createsSession_thenPollsEveryInterval() {
        val vm = vm()
        vm.resumePairing()
        dispatcher.scheduler.runCurrent()
        val st = vm.state.value
        assertEquals(PairPhase.Waiting, st.pair)
        assertEquals("8493-2017", st.pairUserCode)
        assertEquals(pair.qrUrl, st.pairUrl)
        assertNotNull(st.pairQr)
        verify(account, never()).tvPairPoll(any())

        dispatcher.scheduler.advanceTimeBy(3_001); dispatcher.scheduler.runCurrent()
        verify(account, times(1)).tvPairPoll(pair)
        dispatcher.scheduler.advanceTimeBy(3_000); dispatcher.scheduler.runCurrent()
        verify(account, times(2)).tvPairPoll(pair)
    }

    @Test
    fun pause_stopsPolling_resumeKeepsSameSession() {
        val longPair = pair.copy(expiresIn = 600)
        whenever(account.tvPairStart(any())).thenReturn(longPair)      // долгая сессия: пауза в 5 с не должна её съесть
        val vm = vm()
        vm.resumePairing(); dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(3_001); dispatcher.scheduler.runCurrent()
        verify(account, times(1)).tvPairPoll(longPair)

        vm.pausePairing()
        dispatcher.scheduler.advanceTimeBy(5_000); dispatcher.scheduler.runCurrent()
        verify(account, times(1)).tvPairPoll(longPair)           // за время паузы ни одного запроса

        vm.resumePairing()
        dispatcher.scheduler.advanceTimeBy(3_001); dispatcher.scheduler.runCurrent()
        verify(account, times(2)).tvPairPoll(longPair)
        verify(account, times(1)).tvPairStart(any())          // сессия прежняя, новый код не запрашивался
    }

    @Test
    fun onCleared_stopsPolling() {
        val vm = vm()
        val store = ViewModelStore()
        val provider = ViewModelProvider.create(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        })
        provider[ScActivationViewModel::class.java]
        vm.resumePairing(); dispatcher.scheduler.runCurrent()

        store.clear()                                          // вызывает onCleared()
        dispatcher.scheduler.advanceTimeBy(30_000); dispatcher.scheduler.runCurrent()
        verify(account, never()).tvPairPoll(any())
    }

    @Test
    fun pairingDisabled_doesNotStart() {
        val vm = vm()
        vm.pairingEnabled = false
        vm.resumePairing(); dispatcher.scheduler.runCurrent()
        verify(account, never()).tvPairStart(any())
    }

    @Test
    fun codeExpires_showsExpired_andStopsPolling() {
        val vm = vm()
        vm.resumePairing(); dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(10_500); dispatcher.scheduler.runCurrent()      // expires_in = 10 с
        val st = vm.state.value
        assertEquals(PairPhase.Expired, st.pair)
        assertNull(st.pairQr)
        val polls = org.mockito.Mockito.mockingDetails(account).invocations.count { it.method.name == "tvPairPoll" }
        dispatcher.scheduler.advanceTimeBy(30_000); dispatcher.scheduler.runCurrent()
        verify(account, times(polls)).tvPairPoll(any())
    }

    @Test
    fun serverSaysExpired_showsExpired_thenRefreshCreatesNewSession() {
        whenever(account.tvPairPoll(any())).thenReturn(ScAccount.TvPoll.Expired)
        val vm = vm()
        vm.resumePairing(); dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(3_001); dispatcher.scheduler.runCurrent()
        assertEquals(PairPhase.Expired, vm.state.value.pair)

        whenever(account.tvPairPoll(any())).thenReturn(ScAccount.TvPoll.Pending)
        vm.newPairing(); dispatcher.scheduler.runCurrent()
        assertEquals(PairPhase.Waiting, vm.state.value.pair)
        verify(account, times(2)).tvPairStart(any())
    }

    @Test
    fun startFailure_showsError() {
        whenever(account.tvPairStart(any())).thenReturn(null)
        val vm = vm()
        vm.resumePairing(); dispatcher.scheduler.runCurrent()
        assertEquals(PairPhase.Error, vm.state.value.pair)
        assertNull(vm.state.value.pairQr)
    }

    @Test
    fun approved_setsActivatedLink_andStopsPolling() {
        whenever(account.tvPairPoll(any())).thenReturn(ScAccount.TvPoll.Approved("vless://x", "active", ""))
        val vm = vm()
        vm.resumePairing(); dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(3_001); dispatcher.scheduler.runCurrent()
        assertEquals("vless://x", vm.state.value.activatedLink)
        dispatcher.scheduler.advanceTimeBy(30_000); dispatcher.scheduler.runCurrent()
        verify(account, times(1)).tvPairPoll(any())
    }

    @Test
    fun masterKey_isTrimmed_andSucceeds() {
        whenever(account.redeem(any())).thenReturn(ScAccount.Redeem.Ok("vless://mk", "masterkey"))
        val vm = vm()
        vm.onCodeChange("  SC-ABCD-EFGH-JKLM \n")
        vm.submit()
        assertTrue(vm.state.value.busy)
        dispatcher.scheduler.advanceUntilIdle()
        verify(account).redeem("SC-ABCD-EFGH-JKLM")
        val st = vm.state.value
        assertEquals("vless://mk", st.activatedLink)
        assertFalse(st.busy)
        assertNull(st.error)
    }

    @Test
    fun masterKey_doubleSubmit_sendsOnce() {
        whenever(account.redeem(any())).thenReturn(ScAccount.Redeem.Ok("vless://mk", "masterkey"))
        val vm = vm()
        vm.onCodeChange("SC-ABCD-EFGH-JKLM")
        vm.submit(); vm.submit()
        dispatcher.scheduler.advanceUntilIdle()
        verify(account, times(1)).redeem(any())
    }

    @Test
    fun masterKey_wrongKey_showsServerError_andUnblocks() {
        whenever(account.redeem(any())).thenReturn(ScAccount.Redeem.Failed("Ключ не найден — проверь, что вставил его целиком"))
        val vm = vm()
        vm.onCodeChange("SC-0000-0000-0000")
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()
        val st = vm.state.value
        assertEquals("Ключ не найден — проверь, что вставил его целиком", st.error)
        assertFalse(st.networkError)
        assertFalse(st.busy)
        assertNull(st.activatedLink)
        vm.onCodeChange("SC-0000-0000-0001")                  // правка поля снимает ошибку
        assertNull(vm.state.value.error)
    }

    @Test
    fun masterKey_networkFailure_flagsNetworkError() {
        whenever(account.redeem(any())).thenReturn(ScAccount.Redeem.Failed(""))
        val vm = vm()
        vm.onCodeChange("SC-ABCD-EFGH-JKLM")
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.networkError)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun emptyInput_isNotSent() {
        val vm = vm()
        vm.onCodeChange("   ")
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()
        verify(account, never()).redeem(any())
        assertFalse(vm.state.value.busy)
    }
}
