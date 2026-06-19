package com.koupper.shared

import java.lang.reflect.AccessibleObject

fun ensureAccessible(member: AccessibleObject) {
    try {
        member.isAccessible = true
    } catch (_: Exception) {
        try {
            val ao = Class.forName("java.lang.reflect.AccessibleObject")
            val m = ao.getMethod("trySetAccessible")
            m.invoke(member)
        } catch (_: Throwable) {
            // JDK internals changed — no accessible fallback available
        }
    }
}
