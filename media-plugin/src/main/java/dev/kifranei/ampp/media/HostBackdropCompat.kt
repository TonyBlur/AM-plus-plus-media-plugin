package dev.kifranei.ampp.media

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import java.lang.reflect.Method

/**
 * Version-tolerant `drawBackdrop`.
 *
 * AM++ changed the compiler-mangled `drawBackdrop-<hash>` signature between releases: newer hosts
 * insert `minLayerSize` and `onSampleMaterial` before `onDrawFront`, so both the total parameter
 * count and the `onDrawSurface` position move. Resolve the host's `drawBackdrop-*$default` bridge
 * reflectively, locate `onDrawSurface` by its `DrawScope` lambda signature (falling back to its
 * position after `onDrawBackdrop`), and pass only backdrop/shape/effects/surface so the host applies
 * its own defaults. If anything is missing or fails, degrade to a plain clipped translucent surface
 * instead of throwing into Apple Music.
 */
internal object HostBackdropCompat {
    private class Bridge(val method: Method, val size: Int, val surfaceIndex: Int)

    @Volatile private var resolved = false
    @Volatile private var bridge: Bridge? = null
    @Volatile private var logged = false

    private const val FUNCTION_1 = "kotlin.jvm.functions.Function1"
    private const val FUNCTION_2 = "kotlin.jvm.functions.Function2"

    private fun resolve(): Bridge? {
        if (resolved) return bridge
        synchronized(this) {
            if (resolved) return bridge
            bridge = runCatching {
                val owner = Class.forName("com.kyant.backdrop.DrawBackdropModifierKt", false, Backdrop::class.java.classLoader)
                owner.declaredMethods.firstNotNullOfOrNull { m ->
                    val types = m.parameterTypes
                    val names = types.map { it.name }
                    val match = java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                        m.name.startsWith("drawBackdrop-") && m.name.endsWith("\$default") &&
                        names.size in 14..20 &&
                        names[0] == "androidx.compose.ui.Modifier" &&
                        names[1] == "com.kyant.backdrop.Backdrop" &&
                        types[types.size - 2] == Int::class.javaPrimitiveType &&
                        names.last() == "java.lang.Object"
                    if (!match) return@firstNotNullOfOrNull null
                    val declared = types.size - 2
                    val surface = surfaceIndex(m, declared)
                    MediaRuntime.log(
                        "backdrop_compat: host drawBackdrop params=${types.size} declared=$declared surface=$surface",
                        null,
                    )
                    Bridge(m, declared, surface)
                }
            }.onFailure { MediaRuntime.log("backdrop_compat: resolve failed", it) }.getOrNull()
            if (bridge == null) MediaRuntime.log("backdrop_compat: no usable drawBackdrop host method; using plain surface")
            resolved = true
            return bridge
        }
    }

    /**
     * `onDrawSurface` is the first `Function1` after the first `Function2` (`onDrawBackdrop`).
     * `onDrawBehind` is an earlier `Function1`, and 1.7.x adds another `Function2`
     * (`onSampleMaterial`) after the surface, so neither "last Function1" nor "last Function2"
     * uniquely identifies it.
     */
    private fun surfaceIndex(method: Method, declared: Int): Int = surfaceIndex(method.parameterTypes, declared)

    internal fun surfaceIndex(types: Array<Class<*>>, declared: Int = types.size): Int {
        val backdrop = (0 until declared).firstOrNull { types[it].name == FUNCTION_2 }
            ?: return (declared - 2).coerceAtLeast(0)
        for (i in backdrop + 1 until declared) if (types[i].name == FUNCTION_1) return i
        return (declared - 2).coerceAtLeast(0)
    }

    /**
     * Kotlin `$default` mask: bit i is the i-th declared parameter, the extension receiver (Modifier)
     * is NOT counted. Declared 0..2 are backdrop/shape/effects (explicit), `onDrawSurface` is explicit,
     * everything else (highlight, shadow, innerShadow, layerBlock, clip, exported, minLayerSize,
     * onDrawBehind, onDrawBackdrop, onSampleMaterial, onDrawFront) takes the host default.
     * [paramCount] is the JVM parameter count of the bridge including the receiver, [surfaceIndex] its
     * JVM index of onDrawSurface.
     */
    internal fun defaultMask(paramCount: Int, surfaceIndex: Int, hostHighlight: Boolean = true): Int {
        val mask = ((1 shl (paramCount - 1)) - 1) and 0b111.inv() and (1 shl (surfaceIndex - 1)).inv()
        // Declared parameter 3 is `highlight`; leaving its bit clear passes the explicit null = no rim.
        return if (hostHighlight) mask else mask and (1 shl 3).inv()
    }

    fun apply(
        modifier: Modifier,
        backdrop: Backdrop,
        shape: () -> Shape,
        effects: BackdropEffectScope.() -> Unit,
        onDrawSurface: DrawScope.() -> Unit,
        hostHighlight: Boolean = true,
        fallbackSurface: DrawScope.() -> Unit = onDrawSurface,
    ): Modifier {
        val host = resolve()
        if (host != null) {
            try {
                val size = host.size
                val args = arrayOfNulls<Any>(size + 2)
                val types = host.method.parameterTypes
                for (i in 0 until size) if (types[i] == Int::class.javaPrimitiveType) args[i] = 0
                    else if (types[i] == Long::class.javaPrimitiveType) args[i] = 0L
                args[0] = modifier; args[1] = backdrop; args[2] = shape; args[3] = effects
                args[host.surfaceIndex] = onDrawSurface
                args[size] = defaultMask(size, host.surfaceIndex, hostHighlight)
                args[size + 1] = null
                return host.method.invoke(null, *args) as Modifier
            } catch (error: Throwable) {
                bridge = null
                if (!logged) { logged = true; MediaRuntime.log("backdrop_compat: host drawBackdrop failed; plain surface", error) }
            }
        }
        return modifier.clip(shape()).drawBehind(fallbackSurface)
    }
}

internal fun Modifier.compatDrawBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    onDrawSurface: DrawScope.() -> Unit,
    hostHighlight: Boolean = true,
    fallbackSurface: DrawScope.() -> Unit = onDrawSurface,
): Modifier = HostBackdropCompat.apply(this, backdrop, shape, effects, onDrawSurface, hostHighlight, fallbackSurface)
