package com.example.httpsbrowser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PipControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val seconds = intent.getIntExtra(MainActivity.EXTRA_PIP_SEEK_SECONDS, 0)
        if (seconds == 0) return
        MainActivity.activeInstance()?.handlePipSeek(seconds)
    }
}
