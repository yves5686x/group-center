package com.khm.group.center.user

import com.khm.group.center.datatype.config.parser.MachineConfigParser
import org.junit.jupiter.api.Test

import com.khm.group.center.utils.file.ProgramFile

class MachineYamlTest {
    @Test
    fun testReadMachineYaml() {
        val path = "./Config/Machine/gpu.yaml"

        // Private (gitignored) machine config; only run when it is present
        org.junit.jupiter.api.Assumptions.assumeTrue(java.io.File(path).exists()) {
            "$path not present; skipping"
        }

        val text = ProgramFile.readFile(path)

        val result = MachineConfigParser.parseMachineYaml(text)

        println(result.size)
    }

    @Test
    fun testReadMachineInDir() {
        val path = "./Config/Machine/Deploy"

        // Private (gitignored) machine configs; only run when the directory is present
        org.junit.jupiter.api.Assumptions.assumeTrue(java.io.File(path).isDirectory()) {
            "$path not present; skipping"
        }

        val result = MachineConfigParser.parseMachineYamlInDir(path)

        println(result.size)
        for (user in result) {
            println(
                "Name:${user.name}\n" +
                        "\tEng:${user.nameEng}\n" +
                        "\tWeComId:${user.webhook.weComServer.groupBotKey}"
            )
        }
    }
}
