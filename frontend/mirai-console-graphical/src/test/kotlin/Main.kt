import net.mamoe.mirai.console.graphical.MiraiConsoleGraphicalLoader
import net.mamoe.mirai.console.graphical.MiraiGraphicalUI
import tornadofx.launch

fun main(args: Array<String>) {
    MiraiConsoleGraphicalLoader.prestart()
    launch<MiraiGraphicalUI>(args)
}