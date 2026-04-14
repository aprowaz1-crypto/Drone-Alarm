package com.aegisf6.app.engine

import com.aegisf6.app.model.ActiveSourceMode
import com.aegisf6.app.model.ForcedSourceMode

object SmartSourceSelector {
    fun resolve(forced: ForcedSourceMode, btMicCount: Int): ActiveSourceMode {
        return when (forced) {
            ForcedSourceMode.PHONE_ONLY -> ActiveSourceMode.PHONE_SOLO
            ForcedSourceMode.ARRAY_ONLY -> {
                if (btMicCount >= 2) ActiveSourceMode.MULTI_ARRAY else ActiveSourceMode.PHONE_SOLO
            }
            ForcedSourceMode.AUTO -> {
                if (btMicCount >= 2) ActiveSourceMode.MULTI_ARRAY else ActiveSourceMode.PHONE_SOLO
            }
        }
    }
}
