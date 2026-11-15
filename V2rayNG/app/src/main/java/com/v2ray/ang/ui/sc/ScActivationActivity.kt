package com.v2ray.ang.ui.sc

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import kotlinx.coroutines.delay

/**
 * Ввод 30-минутного кода активации и экран блокировки DEVICE_LOCKED (ключ привязан к другому устройству, 72 ч).
 * Результат: RESULT_OK + EXTRA_LINK — ScActivity ставит подписку и подключается.
 */
class ScActivationActivity : BaseComponentActivity() {
    companion object {
        const val EXTRA_LINK = "link"
        fun intent(ctx: Context) = Intent(ctx, ScActivationActivity::class.java)
    }

    private val vm: ScActivationViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        val st by vm.state.collectAsStateWithLifecycle()
        LaunchedEffect(st.activatedLink) {
            st.activatedLink?.let {
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_LINK, it))
                finish()
            }
        }
        Scaffold { inner ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .consumeWindowInsets(inner)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (st.lockUntilMs > 0L) LockContent(st.lockUntilMs, st.lockDeviceName, vm::onLockExpired)
                else CodeContent(st, vm::onCodeChange, vm::submit)
            }
        }
    }
}

@Composable
private fun CodeContent(st: ScActivationUiState, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    Text(stringResource(R.string.sc_act_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.sc_act_hint), style = MaterialTheme.typography.bodyMedium)
    val err = st.error ?: if (st.networkError) stringResource(R.string.sc_act_network) else null
    OutlinedTextField(
        value = st.code,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.sc_act_code_label)) },
        singleLine = true,
        isError = err != null,
        supportingText = err?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        enabled = !st.busy,
    )
    Button(onClick = onSubmit, enabled = !st.busy && st.code.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (st.busy) R.string.sc_act_checking else R.string.sc_act_button))
    }
}

@Composable
private fun LockContent(untilMs: Long, deviceName: String, onExpired: () -> Unit) {
    var left by remember(untilMs) { mutableLongStateOf(untilMs - System.currentTimeMillis()) }
    LaunchedEffect(untilMs) {
        while (true) {
            left = untilMs - System.currentTimeMillis()
            if (left <= 0L) { onExpired(); break }
            delay(1000)
        }
    }
    val s = (left.coerceAtLeast(0L) / 1000)
    val time = "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    Text(stringResource(R.string.sc_lock_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        if (deviceName.isBlank()) stringResource(R.string.sc_lock_body_generic)
        else stringResource(R.string.sc_lock_body, deviceName),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(stringResource(R.string.sc_lock_timer, time), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.sc_lock_hint), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun RebindContent(st: ScActivationUiState, onRebind: () -> Unit) {
    Text(stringResource(R.string.sc_rebind_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        if (st.rebindDeviceName.isBlank()) stringResource(R.string.sc_rebind_body_generic)
        else stringResource(R.string.sc_rebind_body, st.rebindDeviceName),
        style = MaterialTheme.typography.bodyMedium,
    )
    val err = st.error ?: if (st.networkError) stringResource(R.string.sc_act_network) else null
    err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Button(onClick = onRebind, enabled = !st.busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (st.busy) R.string.sc_act_checking else R.string.sc_rebind_button))
    }
}