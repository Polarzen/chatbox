package dev.polarzen.chatbox

import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
internal fun ScanDevicePairButton(onScanned: (DevicePairCode) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) {
            val pair = try {
                decodeDevicePairCode(contents)
            } catch (_: IllegalArgumentException) {
                onError("请扫描电脑显示的 Chatbox 设备配对二维码。")
                return@rememberLauncherForActivityResult
            }
            onScanned(pair)
        } else if (result.originalIntent?.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, false) == true) {
            onError("扫码需要相机权限，也可以手动输入地址和配对码。")
        } else {
            onError("扫码已结束，可以重新扫码或手动输入。")
        }
    }
    OutlinedButton(onClick = {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            onError("此设备没有可用相机，请手动输入地址和配对码。")
        } else {
            val options = ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("扫描电脑上的 Chatbox 设备配对二维码")
                .setBeepEnabled(false)
                .addExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, false)
            try {
                scanner.launch(options)
            } catch (_: ActivityNotFoundException) {
                onError("无法打开扫码界面，请手动输入地址和配对码。")
            } catch (_: SecurityException) {
                onError("相机访问受到限制，请手动输入地址和配对码。")
            }
        }
    }) {
        Text("扫描电脑二维码")
    }
}
