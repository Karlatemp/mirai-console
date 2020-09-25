/*
 * Copyright 2019-2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AFFERO GENERAL PUBLIC LICENSE version 3 license that can be found via the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

package net.mamoe.mirai.console.graphical

import com.jfoenix.adapters.ReflectionHelper
import io.github.karlatemp.unsafeaccessor.Unsafe
import tornadofx.launch
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Field
import java.lang.reflect.Modifier

object MiraiConsoleGraphicalLoader {
    fun prestart() {

        val field = ReflectionHelper::class.java.getDeclaredField("objectFieldOffset")
        field.isAccessible = true
        if ((field[null] as Long) != 0L) return
        val unsafe = Unsafe.getUnsafe()
        val mhl = MethodHandles.Lookup::class.java
        val lookupClassOffset = unsafe.objectFieldOffset(mhl, "lookupClass")
        val allowedModesOffset = unsafe.objectFieldOffset(mhl, "allowedModes")
        val rootLookup = MethodHandles.lookup()
        // make it root
        unsafe.putReference(rootLookup, lookupClassOffset, java.lang.Object::class.java)
        unsafe.putInt(rootLookup, allowedModesOffset, -1)
        // getDeclaredFields0(publicOnly: Boolean)
        @Suppress("UNCHECKED_CAST") val fields = rootLookup.findVirtual(
            Class::class.java, "getDeclaredFields0",
            MethodType.methodType(
                /* ret   = */ Array<Field>::class.java,
                /* types = */ java.lang.Boolean.TYPE
            )
        ).invokeWithArguments(AccessibleObject::class.java, /* publicOnly */false) as Array<Field>
        field.setLong(null, unsafe.objectFieldOffset(fields.first {
            !Modifier.isStatic(it.modifiers) && it.type == java.lang.Boolean.TYPE
        }))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        prestart()
        launch<MiraiGraphicalUI>(args)
    }
}