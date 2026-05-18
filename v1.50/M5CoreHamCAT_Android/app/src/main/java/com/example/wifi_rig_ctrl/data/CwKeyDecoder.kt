package com.example.wifi_rig_ctrl.data

/**
 * TX用 CWデコーダ: キーON/OFFイベントのタイミングからモールス符号を復元する
 *
 * USBキーイベント（isOn）を受け取り dit/dah/スペースを判定して文字をデコードする。
 * keyEvent() と checkTimeout() は複数スレッドから呼ばれるため synchronized。
 */
class CwKeyDecoder {

    var onCharDecoded: ((Char, Boolean) -> Unit)? = null  // (char, isTx=true)

    // 適応 dit 長 (ms, 初期値 80ms ≒ 12.5 WPM)
    @get:Synchronized
    var ditMs = 80L
        private set

    private val elements = StringBuilder()
    private var lastKeyOnMs  = 0L
    private var lastKeyOffMs = 0L
    private var keyIsOn = false
    private var charFlushed = false
    private var wordEmitted = false

    @Synchronized
    fun keyEvent(isOn: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val now = nowMs
        if (isOn && !keyIsOn) {
            val offDur = if (lastKeyOffMs > 0L) now - lastKeyOffMs else 0L
            if (offDur > 0) analyzeGap(offDur)
            lastKeyOnMs = now
            keyIsOn = true
        } else if (!isOn && keyIsOn) {
            val onDur = now - lastKeyOnMs
            analyzeElement(onDur)
            lastKeyOffMs = now
            keyIsOn = false
            charFlushed = false
            wordEmitted = false
        }
    }

    /** キーオフのままタイムアウトした場合に呼ぶ。viewModelScope から遅延起動する。 */
    @Synchronized
    fun checkTimeout() {
        if (keyIsOn || lastKeyOffMs == 0L) return
        val offDur = System.currentTimeMillis() - lastKeyOffMs
        if (!charFlushed && offDur >= ditMs * 3) {
            flushCharacter(); charFlushed = true
        }
        if (!wordEmitted && offDur >= ditMs * 7) {
            onCharDecoded?.invoke(' ', true); wordEmitted = true
        }
    }

    @Synchronized
    fun reset() {
        ditMs = 80L; elements.clear()
        lastKeyOnMs = 0L; lastKeyOffMs = 0L; keyIsOn = false
        charFlushed = false; wordEmitted = false
    }

    private fun analyzeElement(durationMs: Long) {
        if (durationMs < ditMs / 4) return  // 25% 未満はノイズ、無視
        if (durationMs < ditMs * 2) {
            elements.append('.')
            ditMs = (ditMs * 0.85f + durationMs * 0.15f).toLong()
        } else {
            elements.append('-')
            ditMs = (ditMs * 0.85f + durationMs / 3f * 0.15f).toLong()
        }
        ditMs = ditMs.coerceIn(15L, 250L)  // 15ms〜250ms
    }

    private fun analyzeGap(durationMs: Long) {
        if (elements.isEmpty() || charFlushed) return
        when {
            durationMs >= ditMs * 6 -> {
                flushCharacter(); charFlushed = true
                if (!wordEmitted) { onCharDecoded?.invoke(' ', true); wordEmitted = true }
            }
            durationMs >= ditMs * 2 -> {
                flushCharacter(); charFlushed = true
            }
        }
    }

    private fun flushCharacter() {
        if (elements.isEmpty()) return
        val c = CwDecoder.MORSE_TABLE[elements.toString()] ?: '?'
        onCharDecoded?.invoke(c, true)
        elements.clear()
    }
}
