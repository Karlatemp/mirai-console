/*
 * Copyright 2019-2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AFFERO GENERAL PUBLIC LICENSE version 3 license that can be found via the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

package net.mamoe.mirai.console.internal.command

import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import net.mamoe.mirai.console.MiraiConsole
import net.mamoe.mirai.console.command.*
import net.mamoe.mirai.console.command.Command.Companion.primaryName
import net.mamoe.mirai.event.Listener
import net.mamoe.mirai.event.subscribeAlways
import net.mamoe.mirai.message.MessageEvent
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.utils.MiraiLogger
import java.util.concurrent.locks.ReentrantLock

internal object CommandManagerImpl : CommandManager, CoroutineScope by CoroutineScope(MiraiConsole.job) {
    private val logger: MiraiLogger by lazy {
        MiraiConsole.newLogger("command")
    }

    @JvmField
    internal val registeredCommands: MutableList<Command> = mutableListOf()

    @JvmField
    internal val requiredPrefixCommandMap: MutableMap<String, Command> = mutableMapOf()

    @JvmField
    internal val optionalPrefixCommandMap: MutableMap<String, Command> = mutableMapOf()

    @JvmField
    internal val modifyLock = ReentrantLock()


    /**
     * 从原始的 command 中解析出 Command 对象
     */
    internal fun matchCommand(rawCommand: String): Command? {
        if (rawCommand.startsWith(commandPrefix)) {
            return requiredPrefixCommandMap[rawCommand.substringAfter(commandPrefix).toLowerCase()]
        }
        return optionalPrefixCommandMap[rawCommand.toLowerCase()]
    }

    internal val commandListener: Listener<MessageEvent> by lazy {
        subscribeAlways(
            coroutineContext = CoroutineExceptionHandler { _, throwable ->
                logger.error(throwable)
            },
            concurrency = Listener.ConcurrencyKind.CONCURRENT,
            priority = Listener.EventPriority.HIGH
        ) {
            val sender = this.toCommandSender()
            // starting parsing
            val cmd = kotlin.run {
                val botSelector = message.asSequence()
                    .filterIsInstance<MessageContent>()
                    // Skip all space before At(bot)(BotSelector)
                    .filter { it !is PlainText || it.content.trim().isNotEmpty() }
                    .firstOrNull()
                if (botSelector is At) {
                    if (botSelector.target != bot.id) {
                        // Selector target not match.
                        return@subscribeAlways
                    }
                    // [MessageSource] [N of Empty PlainText (worst)] [At(BotSelector)][Command (need trim-left)]
                    val result = ArrayList(message)
                    val iterator = result.iterator()
                    // Remove ( [N of Empty PlainText (worst)] [BotSelector] )
                    looping@ while (iterator.hasNext()) {
                        when (iterator.next()) {
                            is MessageSource -> {
                                // Skip
                            }
                            is PlainText -> {
                                // Remove empty placeholders before BotSelector
                                // The previous logic layer has determined that these PlainText are all empty
                                iterator.remove()
                            }
                            is At -> {
                                // Remove the bot selector, then stop removing.
                                // The first At(BotSelector) is the last element to delete in this step.
                                iterator.remove()
                                break@looping
                            }
                        }
                    }
                    // Trim-left the command.
                    looping@ for (i in result.indices) {
                        when (val content = result[i]) {
                            is MessageSource -> {
                                // Skip
                            }
                            is PlainText -> {
                                // trim-left the first plain text
                                result[i] = PlainText(content.content.trimStart())
                                // Trim-left finished. Quit the loop.
                                break@looping
                            }
                            // Command Message Chain's left side has no space.
                            else -> break@looping
                        }
                    }
                    return@run result.asMessageChain()
                }
                message
            }
            when (val result = sender.executeCommand(cmd)) {
                is CommandExecuteResult.PermissionDenied -> {
                    if (!result.command.prefixOptional) {
                        sender.sendMessage("权限不足")
                        intercept()
                    }
                }
                is CommandExecuteResult.Success -> {
                    intercept()
                }
                is CommandExecuteResult.ExecutionFailed -> {
                    sender.catchExecutionException(result.exception)
                    intercept()
                }
                is CommandExecuteResult.CommandNotFound -> {
                    // noop
                }
            }
        }
    }


    ///// IMPL


    override val CommandOwner.registeredCommands: List<Command> get() = CommandManagerImpl.registeredCommands.filter { it.owner == this }
    override val allRegisteredCommands: List<Command> get() = registeredCommands.toList() // copy
    override val commandPrefix: String get() = "/"
    override fun CommandOwner.unregisterAllCommands() {
        for (registeredCommand in registeredCommands) {
            registeredCommand.unregister()
        }
    }

    override fun Command.register(override: Boolean): Boolean {
        if (this is CompositeCommand) this.subCommands // init

        modifyLock.withLock {
            if (!override) {
                if (findDuplicate() != null) return false
            }
            registeredCommands.add(this@register)
            if (this.prefixOptional) {
                for (name in this.names) {
                    val lowerCaseName = name.toLowerCase()
                    optionalPrefixCommandMap[lowerCaseName] = this
                    requiredPrefixCommandMap[lowerCaseName] = this
                }
            } else {
                for (name in this.names) {
                    val lowerCaseName = name.toLowerCase()
                    optionalPrefixCommandMap.remove(lowerCaseName) // ensure resolution consistency
                    requiredPrefixCommandMap[lowerCaseName] = this
                }
            }
            return true
        }
    }

    override fun Command.findDuplicate(): Command? =
        registeredCommands.firstOrNull { it.names intersectsIgnoringCase this.names }

    override fun Command.unregister(): Boolean = modifyLock.withLock {
        if (this.prefixOptional) {
            this.names.forEach {
                optionalPrefixCommandMap.remove(it)
            }
        }
        this.names.forEach {
            requiredPrefixCommandMap.remove(it)
        }
        registeredCommands.remove(this)
    }

    override fun Command.isRegistered(): Boolean = this in registeredCommands

    //// executing without detailed result (faster)
    override suspend fun Command.execute(sender: CommandSender, args: MessageChain, checkPermission: Boolean) {
        sender.executeCommandInternal(
            this,
            args.flattenCommandComponents().toTypedArray(),
            this.primaryName,
            checkPermission
        )
    }

    override suspend fun Command.execute(sender: CommandSender, vararg args: Any, checkPermission: Boolean) {
        sender.executeCommandInternal(
            this,
            args.flattenCommandComponents().toTypedArray(),
            this.primaryName,
            checkPermission
        )
    }

    //// execution with detailed result
    override suspend fun CommandSender.executeCommand(vararg messages: Any): CommandExecuteResult {
        if (messages.isEmpty()) return CommandExecuteResult.CommandNotFound("")
        return executeCommandInternal(messages, messages[0].toString().substringBefore(' '))
    }

    override suspend fun CommandSender.executeCommand(messages: MessageChain): CommandExecuteResult {
        val msg = messages.filterIsInstance<MessageContent>()
        if (msg.isEmpty()) return CommandExecuteResult.CommandNotFound("")
        return executeCommandInternal(msg, msg[0].toString().substringBefore(' '))
    }
}