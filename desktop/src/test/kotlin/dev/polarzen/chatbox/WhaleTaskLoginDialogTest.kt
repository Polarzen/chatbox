package dev.polarzen.chatbox

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WhaleTaskLoginDialogTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun pluginDiscoveryFailuresGiveActionableLocalGuidance() {
        for (message in listOf("任务插件返回的会话编号无效。", "找不到任务接口，请检查 AstrBot 和任务插件版本。", "任务接口返回 HTTP 503。")) {
            assertEquals("任务接口尚不可用，请检查鲸鱼任务插件是否已启用并重载。", safeFailureMessage(IllegalStateException(message)))
        }
        assertEquals("当前账号无权使用鲸鱼任务，请检查插件的管理账号绑定。",
            safeFailureMessage(IllegalStateException("当前 Dashboard 账号无权使用此任务会话，请检查插件绑定。")))
    }

    @Test
    fun loginRequiresPasswordAndAcceptsOptionalSixDigitCode() {
        val submissions = mutableListOf<List<String>>()
        ui.setContent {
            WhaleTaskLoginDialog("http://127.0.0.1:6185", {}) { username, password, code ->
                submissions.add(listOf(username, password, code))
                error("sensitive test message")
            }
        }
        ui.onNodeWithText("目标实例　http://127.0.0.1:6185").assertIsDisplayed()
        ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).assertCountEquals(2)
        val submit = ui.onNodeWithText("登录")
        ui.onNodeWithText("Dashboard 用户名").performTextInput("admin")
        submit.assertIsNotEnabled()
        ui.onNodeWithText("密码").performTextInput("test-password")
        submit.assertIsEnabled()
        val otp = ui.onNodeWithText("六位验证码（可选）")
        otp.performTextInput("123")
        submit.assertIsNotEnabled()
        otp.performTextInput("456")
        submit.performClick()
        ui.onNodeWithText("登录失败，请检查实例连接、用户名、密码及验证码后重试。").assertIsDisplayed()
        ui.onNodeWithText("sensitive test message").assertDoesNotExist()
        submit.assertIsNotEnabled()
        ui.runOnIdle { assertEquals(listOf(listOf("admin", "test-password", "123456")), submissions) }
    }
}
