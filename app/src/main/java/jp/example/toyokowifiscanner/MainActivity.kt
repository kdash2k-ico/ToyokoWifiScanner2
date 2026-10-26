package jp.example.toyokowifiscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MainActivity : AppCompatActivity() {

    private lateinit var resultText: TextView
    private lateinit var ssidText: TextView
    private lateinit var passwordText: TextView

    private var ssid = ""
    private var password = ""

    private val cameraLauncher =
        registerForActivityResult(
            ActivityResultContracts.TakePicturePreview()
        ) { bitmap: Bitmap? ->
            if (bitmap == null) {
                resultText.text = "撮影がキャンセルされました"
            } else {
                readText(bitmap)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "東横INN Wi-Fiスキャナー"
            textSize = 24f
        }

        resultText = TextView(this).apply {
            text = "テレビのWi-Fi情報画面を撮影してください"
            textSize = 16f
        }

        ssidText = TextView(this).apply {
            text = "SSID：未検出"
            textSize = 18f
        }

        passwordText = TextView(this).apply {
            text = "パスワード：未検出"
            textSize = 18f
        }

        val captureButton = Button(this).apply {
            text = "撮影して読み取る"
            setOnClickListener {
                resultText.text = "カメラを起動しています……"
                cameraLauncher.launch(null)
            }
        }

        val copySsidButton = Button(this).apply {
            text = "SSIDをコピー"
            setOnClickListener { copyText("SSID", ssid) }
        }

        val copyPasswordButton = Button(this).apply {
            text = "パスワードをコピー"
            setOnClickListener { copyText("パスワード", password) }
        }

        layout.addView(title)
        layout.addView(captureButton)
        layout.addView(ssidText)
        layout.addView(copySsidButton)
        layout.addView(passwordText)
        layout.addView(copyPasswordButton)
        layout.addView(resultText)

        setContentView(layout)
    }

    private fun readText(bitmap: Bitmap) {
        resultText.text = "文字を読み取っています……"

        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text

                ssid = findValue(
                    text,
                    listOf("SSID", "Wi-Fi名", "ネットワーク名")
                )

                password = findValue(
                    text,
                    listOf("パスワード", "PASSWORD", "PASS", "暗号化キー")
                )

                ssidText.text =
                    if (ssid.isNotBlank()) "SSID：$ssid"
                    else "SSID：未検出"

                passwordText.text =
                    if (password.isNotBlank()) "パスワード：$password"
                    else "パスワード：未検出"

                resultText.text =
                    if (text.isBlank()) "文字を読み取れませんでした"
                    else "読み取った文字：\n\n$text"
            }
            .addOnFailureListener { error ->
                resultText.text =
                    "読み取り失敗：${error.message}"
            }
    }

    private fun findValue(text: String, labels: List<String>): String {
        val lines = text.lines()

        for (i in lines.indices) {
            val line = lines[i].trim()

            for (label in labels) {
                if (line.contains(label, ignoreCase = true)) {
                    val value = line.substringAfter(label, "")
                        .trim()
                        .trimStart(':', '：', '=', ' ')

                    if (value.isNotBlank()) return value

                    if (i + 1 < lines.size) {
                        val next = lines[i + 1].trim()
                        if (next.isNotBlank()) return next
                    }
                }
            }
        }

        return ""
    }

    private fun copyText(label: String, value: String) {
        if (value.isBlank()) {
            resultText.text = "$label が見つかっていません"
            return
        }

        val clipboard = getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText(label, value)
        )

        resultText.text = "$label をコピーしました"
    }
}
