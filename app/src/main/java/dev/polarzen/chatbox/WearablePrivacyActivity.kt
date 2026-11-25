package dev.polarzen.chatbox

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class WearablePrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "手环数据的使用"
        val inset = (24 * resources.displayMetrics.density).toInt()
        val explanation = TextView(this).apply {
            setPadding(inset, inset, inset, inset)
            textSize = 17f
            text = "Chatbox 按你在“手环数据”中选择的范围，从健康数据共享读取步数、睡眠、运动和运动心率。记录保留来源和测量时间。\n\n" +
                "启用后，手机通过已配对的 HTTPS 服务同步数据，供你授权的个人 AstrBot 档案查询。后台读取需要系统单独授权。打开主动提醒后，摘要可发送到所选个人会话。\n\n" +
                "你可以在 Chatbox 设置中停止共享或清除已同步的缓存，也可以在系统设置中撤回各项权限。手机离线时，服务会在重新连通后收到撤销。已发到聊天的内容仍保存在相应会话中。"
        }
        setContentView(ScrollView(this).apply { addView(explanation) })
    }
}
