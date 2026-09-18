package com.khm.group.center.datatype.config

import com.khm.group.center.datatype.config.dashboard.DashBoardSiteConfig
import com.khm.group.center.test.H2DatabaseTest
import org.junit.jupiter.api.Test

@H2DatabaseTest
class DashBoardSiteConfigTest {
    @Test
    fun testRead() {
        DashBoardSiteConfig.readDashboardSiteYamlFile()

        println("Size: ${DashBoardSiteConfig.siteClassList.size}")

        DashBoardSiteConfig.siteClassList.forEach {
            println(it.className)
            it.sites.forEach {
                println(it.name)
            }
        }
    }
}
