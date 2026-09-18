package com.khm.group.center.user

import com.khm.group.center.datatype.config.parser.GroupUserConfigParser
import org.junit.jupiter.api.Test

import com.khm.group.center.utils.file.ProgramFile

class UserYamlTest {
    @Test
    fun testReadUserYaml() {
//        val path = "./Config/Users/Master/2023.yaml"
        val path = "./Config/Users/PhD/phd.yaml"

        // Private (gitignored) user config; only run when it is present
        org.junit.jupiter.api.Assumptions.assumeTrue(java.io.File(path).exists()) {
            "$path not present; skipping"
        }

        val text = ProgramFile.readFile(path).trim()

        assert(text.isNotEmpty())

        val result = GroupUserConfigParser.parseUserYaml(text)
        println(result.size)
        for (user in result) {
            println(
                "Name:${user.name}\n" +
                        "\tEng:${user.nameEng}\n" +
                        "\tWeComId:${user.webhook.weCom.userId}"
            )
        }
    }

    @Test
    fun testReadUserInDir() {
        val path = "./Config/Users"

        val result = GroupUserConfigParser.parseUserYamlInDir(path)

        // Repo only ships example configs; skip when no real user configs are present
        org.junit.jupiter.api.Assumptions.assumeTrue(result.isNotEmpty()) {
            "No user yaml configs found in $path; skipping"
        }

        println(result.size)
        for (user in result) {
            println(
                "Name:${user.name}\n" +
                        "\tEng:${user.nameEng}\n" +
                        "\tWeComId:${user.webhook.weCom.userId}"
            )
        }
    }
}
