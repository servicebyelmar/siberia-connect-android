package com.v2ray.ang.ui.sc

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.SiberiaColors

/**
 * Единый стартовый экран входа (телефон, Android TV): два равнозначных блока —
 *   1) «Быстрый вход»: QR + короткий код (device flow, ВМ опрашивает /tv/pair/poll раз в `interval` секунд);
 *   2) «У вас уже есть мастер-ключ?»: поле, «Вставить», «Войти по ключу», «Где взять ключ?».
 * Шире 720 dp блоки встают в две колонки, уже — один под другим. Экран сам не прокручивается: прокрутку даёт хост.
 * Опрос QR живёт только пока экран в состоянии STARTED (ON_STOP останавливает его мгновенно).
 */
@Composable
fun ScAuthContent(
    st: ScActivationUiState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onRefreshPair: () -> Unit,
    onResumePair: () -> Unit,
    onPausePair: () -> Unit,
) {
    // вход только по мастер-ключу: QR/опрос пары не запускаем (onResumePair/onPausePair не вызываются)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                KeyBlock(st, onCodeChange, onSubmit, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                KeyBlock(st, onCodeChange, onSubmit, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Card(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .background(SiberiaColors.Card, RoundedCornerShape(18.dp))
            .border(1.dp, SiberiaColors.Border, RoundedCornerShape(18.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun QrBlock(st: ScActivationUiState, onRefresh: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    Card(modifier) {
        Text(stringResource(R.string.sc_auth_qr_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val qrDesc = stringResource(R.string.sc_auth_qr_desc)
        Box(
            Modifier.size(232.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = st.pairQr
            if (bmp != null && st.pair == PairPhase.Waiting) {
                Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().semantics { contentDescription = qrDesc })
            } else {
                // поверх «пустого» QR — состояние: загрузка / время вышло / ошибка; кнопка «Обновить код» (D-pad: фокус сюда же)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (st.pair) {
                        PairPhase.Loading -> Text(stringResource(R.string.sc_auth_loading), color = Color(0xFF10151A))
                        PairPhase.Expired -> Text(stringResource(R.string.sc_auth_expired), color = Color(0xFFC9B88F))
                        PairPhase.Error -> Text(stringResource(R.string.sc_auth_pair_error), color = Color(0xFFC78F96))
                        PairPhase.Waiting -> Unit
                    }
                    if (st.pair != PairPhase.Loading) {
                        FocusButton(onClick = onRefresh) { Text(stringResource(R.string.sc_auth_refresh)) }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.sc_auth_qr_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = SiberiaColors.Muted,
        )
        if (st.pairUserCode.isNotEmpty() && st.pair == PairPhase.Waiting) {
            Text(stringResource(R.string.sc_auth_code_caption), style = MaterialTheme.typography.bodySmall, color = SiberiaColors.Muted)
            Text(
                st.pairUserCode,
                fontFamily = FontFamily.Monospace,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                color = SiberiaColors.Accent,
            )
            Text(stringResource(R.string.sc_auth_waiting), style = MaterialTheme.typography.bodySmall, color = SiberiaColors.Muted)
            if (st.pairUrl.startsWith("https://")) {
                FocusButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(st.pairUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, primary = false) { Text(stringResource(R.string.sc_auth_open)) }
            }
        }
    }
}

@Composable
private fun KeyBlock(
    st: ScActivationUiState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val err = st.error ?: if (st.networkError) stringResource(R.string.sc_act_network) else null
    Card(modifier) {
        Text(stringResource(R.string.sc_auth_key_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = st.code,
            onValueChange = onCodeChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.sc_auth_key_label)) },
            singleLine = true,
            isError = err != null,
            supportingText = err?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            enabled = !st.busy,
        )
        st.waitMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FocusButton(
                onClick = { clipboard.getText()?.text?.let { onCodeChange(it.trim()) } },
                enabled = !st.busy,
                primary = false,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.sc_auth_paste)) }
            FocusButton(
                onClick = onSubmit,
                enabled = !st.busy && st.code.isNotBlank(),
                modifier = Modifier.weight(2f),
            ) { Text(stringResource(if (st.busy) R.string.sc_act_checking else R.string.sc_auth_login)) }
        }
        Text(stringResource(R.string.sc_auth_where), style = MaterialTheme.typography.bodyMedium, color = SiberiaColors.Muted)
    }
}

/** Окно «Подробнее»: «Назад» (и пульт, и жест) закрывает окно — это штатное поведение AlertDialog, активити не затрагивается. */
@Composable
private fun HelpDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = SiberiaColors.Card,
        title = { Text(stringResource(R.string.sc_auth_help_title)) },
        text = { Text(stringResource(R.string.sc_auth_help_body)) },
        confirmButton = { FocusButton(onClick = onClose) { Text(stringResource(R.string.sc_auth_help_close)) } },
    )
}

/** Кнопка с заметной рамкой фокуса для пульта ТВ (D-pad) и клавиатуры. */
@Composable
private fun FocusButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val m = modifier.then(if (focused) Modifier.border(3.dp, SiberiaColors.Accent, RoundedCornerShape(24.dp)) else Modifier)
    if (primary) Button(onClick = onClick, modifier = m, enabled = enabled, interactionSource = src, content = content)
    else OutlinedButton(onClick = onClick, modifier = m, enabled = enabled, interactionSource = src, content = content)
}
