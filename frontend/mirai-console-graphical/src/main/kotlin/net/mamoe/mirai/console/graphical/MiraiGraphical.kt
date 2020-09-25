/*
 * Copyright 2019-2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AFFERO GENERAL PUBLIC LICENSE version 3 license that can be found via the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

package net.mamoe.mirai.console.graphical

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import net.mamoe.mirai.console.MiraiConsole
import net.mamoe.mirai.console.MiraiConsoleImplementation.Companion.start
import net.mamoe.mirai.console.graphical.controller.MiraiConsoleImplementationGraphical
import net.mamoe.mirai.console.graphical.controller.MiraiGraphicalFrontEndController
import net.mamoe.mirai.console.graphical.stylesheet.PrimaryStyleSheet
import net.mamoe.mirai.console.graphical.view.Decorator
import tornadofx.App
import tornadofx.find
import kotlin.system.exitProcess

//object MiraiGraphicalLoader {
//    @JvmStatic
//    fun main(args: Array<String>) {
//        launch<MiraiGraphicalUI>(args)
//    }
//}

class MiraiGraphicalUI : App(Decorator::class, PrimaryStyleSheet::class) {

    override fun init() {
        super.init()
        val a = find<MiraiGraphicalFrontEndController>()
        val impl = MiraiConsoleImplementationGraphical().also { it.controller = a }
        a.graphical = impl
        impl.start()
    }

    override fun stop() {
        super.stop()
        runBlocking {
            MiraiConsole.job.cancelAndJoin()
        }
        exitProcess(0)
    }
}
