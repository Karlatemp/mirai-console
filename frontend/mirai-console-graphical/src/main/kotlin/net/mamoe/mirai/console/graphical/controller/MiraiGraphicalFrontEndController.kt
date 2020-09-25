/*
 * Copyright 2019-2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AFFERO GENERAL PUBLIC LICENSE version 3 license that can be found via the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

@file:Suppress(
    "INVISIBLE_MEMBER",
    "INVISIBLE_REFERENCE",
    "CANNOT_OVERRIDE_INVISIBLE_MEMBER",
    "INVISIBLE_SETTER",
    "INVISIBLE_GETTER",
    "INVISIBLE_ABSTRACT_MEMBER_FROM_SUPER",
    "INVISIBLE_ABSTRACT_MEMBER_FROM_SUPER_WARNING",
    "EXPOSED_SUPER_CLASS"
)
@file:OptIn(ConsoleInternalApi::class, ConsoleFrontEndImplementation::class)

package net.mamoe.mirai.console.graphical.controller

import javafx.application.Platform
import javafx.collections.ObservableList
import javafx.stage.Modality
import javafx.stage.StageStyle
import kotlinx.coroutines.*
import net.mamoe.mirai.Bot
import net.mamoe.mirai.console.ConsoleFrontEndImplementation
import net.mamoe.mirai.console.MiraiConsole
import net.mamoe.mirai.console.MiraiConsoleFrontEndDescription
import net.mamoe.mirai.console.MiraiConsoleImplementation
import net.mamoe.mirai.console.command.BuiltInCommands
import net.mamoe.mirai.console.command.CommandExecuteStatus
import net.mamoe.mirai.console.command.CommandManager
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.executeCommand
import net.mamoe.mirai.console.command.ConsoleCommandSender
import net.mamoe.mirai.console.data.MultiFilePluginDataStorage
import net.mamoe.mirai.console.data.PluginDataStorage
import net.mamoe.mirai.console.graphical.event.ReloadEvent
import net.mamoe.mirai.console.graphical.model.*
import net.mamoe.mirai.console.graphical.util.getValue
import net.mamoe.mirai.console.graphical.view.dialog.InputDialog
import net.mamoe.mirai.console.graphical.view.dialog.VerificationCodeFragment
import net.mamoe.mirai.console.plugin.jvm.JvmPluginLoader
import net.mamoe.mirai.console.plugin.loader.PluginLoader
import net.mamoe.mirai.console.util.*
import net.mamoe.mirai.message.data.Message
import net.mamoe.mirai.network.CustomLoginFailedException
import net.mamoe.mirai.utils.*
import net.mamoe.mirai.utils.SimpleLogger.LogPriority
import tornadofx.*
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume

@ConsoleExperimentalApi
class MiraiConsoleImplementationGraphical
@JvmOverloads constructor(
    override val rootPath: Path = Paths.get(".").toAbsolutePath(),
    override val builtInPluginLoaders: List<Lazy<PluginLoader<*, *>>> = listOf(lazy { JvmPluginLoader }),
    override val frontEndDescription: MiraiConsoleFrontEndDescription = ConsoleFrontEndDescImpl,
    override val consoleCommandSender: MiraiConsoleImplementation.ConsoleCommandSenderImpl = ConsoleCommandSenderImplGraphical,
    override val dataStorageForJvmPluginLoader: PluginDataStorage = MultiFilePluginDataStorage(rootPath.resolve("data")),
    override val dataStorageForBuiltIns: PluginDataStorage = MultiFilePluginDataStorage(rootPath.resolve("data")),
    override val configStorageForJvmPluginLoader: PluginDataStorage = MultiFilePluginDataStorage(rootPath.resolve("config")),
    override val configStorageForBuiltIns: PluginDataStorage = MultiFilePluginDataStorage(rootPath.resolve("config")),
) : MiraiConsoleImplementation, CoroutineScope by CoroutineScope(
    NamedSupervisorJob("MiraiConsoleImplementationTerminal") +
        CoroutineExceptionHandler { coroutineContext, throwable ->
            if (throwable is CancellationException) {
                return@CoroutineExceptionHandler
            }
            val coroutineName = coroutineContext[CoroutineName]?.name ?: "<unnamed>"
            MiraiConsole.mainLogger.error("Exception in coroutine $coroutineName", throwable)
        }) {
    override val consoleInput: ConsoleInput = object : ConsoleInput {
        override suspend fun requestInput(hint: String): String = controller.requestInput(hint)
    }

    override fun createLoginSolver(requesterBot: Long, configuration: BotConfiguration): LoginSolver = controller.loginSolver

    override fun createLogger(identity: String?): MiraiLogger = controller.LoggerCreator(identity)

    init {
        with(rootPath.toFile()) {
            mkdir()
            require(isDirectory) { "rootDir $absolutePath is not a directory" }
        }
    }

    lateinit var controller: MiraiGraphicalFrontEndController
}


internal object ConsoleCommandSenderImplGraphical : MiraiConsoleImplementation.ConsoleCommandSenderImpl {
    val ui by lazy { find<MiraiGraphicalFrontEndController>() }
    override suspend fun sendMessage(message: String) {
        ui.run {
            Platform.runLater {
                val time = ui.sdf.format(Date())
                mainLog.apply {
                    add("[$time] $message" to "INFO")
                    trim()
                }
            }
        }
    }

    override suspend fun sendMessage(message: Message) {
        return sendMessage(message.toString())
    }
}

private object ConsoleFrontEndDescImpl : MiraiConsoleFrontEndDescription {
    override val name: String get() = "Graphical"
    override val vendor: String get() = "Mamoe Technologies"
    override val version: SemVersion = net.mamoe.mirai.console.internal.MiraiConsoleBuildConstants.version
}

class MiraiGraphicalFrontEndController : Controller() {
    lateinit var graphical: MiraiConsoleImplementationGraphical
    fun Throwable?.rend(): String {
        if (this == null) return ""
        return StringWriter().also { writer ->
            PrintWriter(writer).use { printStackTrace(it) }
        }.toString()
    }

    internal val LoggerCreator: (identity: String?) -> MiraiLogger = {
        SimpleLogger(null) { priority: LogPriority, message: String?, e: Throwable? ->
            Platform.runLater {
                val time = sdf.format(Date())
                mainLog.apply {
                    add("[$time] $message${e.rend()}" to priority.name)
                    trim()
                }
            }
        }
    }

    internal val sdf by ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss") }

    internal val settingModel = find<GlobalSettingModel>()
    internal val loginSolver = GraphicalLoginSolver()
    internal val cache = mutableMapOf<Long, BotModel>()
    val mainLog = observableListOf<Pair<String, String>>()


    val botList = observableListOf<BotModel>()
    val pluginList: ObservableList<PluginModel> by lazy(::getPluginsFromConsole)

    private val consoleInfo = ConsoleInfo()

    init {
        // 监听插件重载事件，以重新从console获取插件列表
        subscribe<ReloadEvent> {
            pluginList.clear()

            // 不能直接赋值，pluginList已经被bind，不能更换对象
            pluginList.addAll(getPluginsFromConsole())
        }
    }

    fun login(qq: String, psd: String) {
        val bot = MiraiConsole.addBot(qq.toLong(), psd)
        MiraiConsole.launch(CoroutineName("Bot $qq login")) { bot.login() }
    }

    fun logout(qq: Long) {
        cache.remove(qq)?.apply {
            botList.remove(this)
            if (botProperty.value != null && bot.isActive) {
                bot.close()
            }
        }
    }

    val consoleLogger by lazy { DefaultLogger("console") }

    fun sendCommand(command: String) {
        runBlocking {
            val next = command.let {
                when {
                    it.isBlank() -> it
                    it.startsWith(CommandManager.commandPrefix) -> it
                    it == "?" -> CommandManager.commandPrefix + BuiltInCommands.HelpCommand.primaryName
                    else -> CommandManager.commandPrefix + it
                }
            }
            if (next.isBlank()) {
                return@runBlocking
            }
            // consoleLogger.debug("INPUT> $next")
            val result = ConsoleCommandSender.executeCommand(next)
            when (result.status) {
                CommandExecuteStatus.SUCCESSFUL -> {
                }
                CommandExecuteStatus.ILLEGAL_ARGUMENT -> {
                    result.exception?.message?.let { consoleLogger.warning(it) }
                }
                CommandExecuteStatus.EXECUTION_EXCEPTION -> {
                    result.exception?.let(consoleLogger::error)
                }
                CommandExecuteStatus.COMMAND_NOT_FOUND -> {
                    consoleLogger.warning("未知指令: ${result.commandName}, 输入 ? 获取帮助")
                }
                CommandExecuteStatus.PERMISSION_DENIED -> {
                    consoleLogger.warning("Permission denied.")
                }
            }
        }
    }


    fun pushVersion(consoleVersion: String, consoleBuild: String, coreVersion: String) {
        Platform.runLater {
            consoleInfo.consoleVersion = consoleVersion
            consoleInfo.consoleBuild = consoleBuild
            consoleInfo.coreVersion = coreVersion
        }
    }

    suspend fun requestInput(hint: String): String =
        suspendCancellableCoroutine {
            Platform.runLater {
                it.resume(InputDialog(hint).open())
            }
        }

    /*
    fun pushBotAdminStatus(identity: Long, admins: List<Long>) = Platform.runLater {
        cache[identity]?.admins?.setAll(admins)
    }
    */

    // fun createLoginSolver(): LoginSolver = loginSolver

    private fun getPluginsFromConsole(): ObservableList<PluginModel> =
        listOf<PluginModel>().toObservable()
//        PluginManager.getAllPluginDescriptions().map(::PluginModel).toObservable()

    /*
    fun checkUpdate(plugin: PluginModel) {
        pluginList.forEach {
            if (it.name == plugin.name && it.author == plugin.author) {
                if (plugin.version > it.version) {
                    it.expired = true
                    return
                }
            }
        }
    }
    */

    /**
     * return `true` when command is ambiguous
     */
    fun checkAmbiguous(plugin: PluginModel): Boolean {
//        plugin.insight?.commands?.forEach { name ->
//            CommandManager.commands.forEach {
//                if (name == it.name) return true
//            }
//        } ?: return false
        return false
    }

    internal fun ObservableList<*>.trim() {
        while (size > settingModel.item.maxLongNum) {
            this.removeAt(0)
        }
    }

//    fun reloadPlugins() {
//
//        with(PluginManager) {
//            reloadPlugins()
//        }
//
//        fire(ReloadEvent) // 广播插件重载事件
//    }
}

class GraphicalLoginSolver : LoginSolver() {
    override suspend fun onSolvePicCaptcha(bot: Bot, data: ByteArray): String? {
        val code = VerificationCodeModel(VerificationCode(data))

        // UI必须在UI线程执行，requestInput在协程种被调用
        Platform.runLater {
            find<VerificationCodeFragment>(Scope(code)).openModal(
                stageStyle = StageStyle.UNDECORATED,
                escapeClosesWindow = false,
                modality = Modality.NONE,
                resizable = false,
                block = true
            )
        }

        // 阻塞协程直到验证码已经输入
        while (code.isDirty || code.code.value == null) {
            delay(1000)
            if (code.code.value === VerificationCodeFragment.MAGIC_KEY) {
                throw LoginCancelledManuallyException()
            }
        }
        return code.code.value
    }


    override suspend fun onSolveSliderCaptcha(bot: Bot, url: String): String? {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override suspend fun onSolveUnsafeDeviceLoginVerify(bot: Bot, url: String): String? {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

class LoginCancelledManuallyException : CustomLoginFailedException(true, "取消登录")