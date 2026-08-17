package com.example.bluetoothserial.data

import com.example.bluetoothserial.model.DataFormat

/**
 * 命令库 TXT 导入导出。
 *
 * 文件格式(每行一条命令,支持 # 注释行):
 *   名称 | HEX | 01 03 00 00 00 02 C4 0B
 *   名称 | ASCII | at+baud=10
 * 格式字段: HEX 或 ASCII(不区分大小写);内容中若包含 | 也能正确识别。
 */
object CommandFileFormat {

    private const val HEADER = "# Zxg蓝牙调试助手 命令库\n# 每行一条命令,格式: 名称 | HEX/ASCII | 内容 ; # 开头为注释\n"

    fun toText(commands: List<CustomCommand>): String {
        val sb = StringBuilder(HEADER)
        commands.forEach { c ->
            sb.append(c.name).append(" | ").append(c.format.name).append(" | ").append(c.data).append("\n")
        }
        return sb.toString()
    }

    /** 解析 TXT 内容为命令列表;非法行自动跳过 */
    fun parse(text: String): List<CustomCommand> {
        val result = mutableListOf<CustomCommand>()
        var id = System.currentTimeMillis()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEach
            val parts = line.split('|').map { it.trim() }
            if (parts.size < 3) return@forEach
            val name = parts[0]
            val formatStr = parts[1]
            // 内容中可能包含 |,重新拼接
            val data = parts.subList(2, parts.size).joinToString("|")
            if (name.isEmpty() || data.isEmpty()) return@forEach
            val format = if (formatStr.equals("ASCII", ignoreCase = true)) DataFormat.ASCII else DataFormat.HEX
            result.add(CustomCommand(id++, name, data, format))
        }
        return result
    }
}
