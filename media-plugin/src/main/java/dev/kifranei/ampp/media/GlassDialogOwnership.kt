package dev.kifranei.ampp.media

import android.app.Dialog
import java.util.WeakHashMap

/**
 * One glass surface per dialog window. PlayerDialogsIntegration (sleep timer / delete confirmation)
 * and PlayerMoreMenuIntegration both react to Dialog.show(); without this claim, both can mount a
 * glass layer on the same window, which looks like two overlapping popups.
 */
internal object GlassDialogOwnership {
    private val owners = WeakHashMap<Dialog, String>()
    private val listeners = mutableListOf<(String) -> Unit>()

    @Synchronized fun onClaim(listener: (String) -> Unit): () -> Unit {
        listeners += listener
        return { removeClaimListener(listener) }
    }

    @Synchronized fun removeClaimListener(listener: (String) -> Unit) { listeners.remove(listener) }

    @Synchronized fun claim(dialog: Dialog, owner: String) {
        owners[dialog] = owner
        listeners.toList().forEach { it(owner) }
    }

    @Synchronized fun owner(dialog: Dialog): String? = owners[dialog]

    @Synchronized fun release(dialog: Dialog) { owners.remove(dialog) }

    @Synchronized fun releaseAll(owner: String) {
        owners.entries.removeAll { it.value == owner }
    }
}
