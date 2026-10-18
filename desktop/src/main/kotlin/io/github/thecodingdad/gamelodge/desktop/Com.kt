package io.github.thecodingdad.gamelodge.desktop

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer

/** Calls method [index] of the COM object [obj] through its vtable (Windows only). */
internal fun comCall(obj: Pointer, index: Int, vararg args: Any?): Int {
    val vtable = obj.getPointer(0)
    return Function.getFunction(vtable.getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
        .invokeInt(arrayOf<Any?>(obj, *args))
}

/** IUnknown::Release. */
internal fun comRelease(obj: Pointer) {
    comCall(obj, 2)
}
