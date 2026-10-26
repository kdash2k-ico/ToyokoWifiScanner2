package jp.example.toyokowifiscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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
        supportActionBar?.hide()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val scroll = ScrollView(this)
        scroll.addView(layout)

        val title = TextView(this).apply {
            text = "東横INN Wi-Fiスキャナー"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        val captureButton = Button(this).apply {
            text = "撮影して読み取る"
            textSize = 18f
            setOnClickListener {
                resultText.text = "カメラを起動しています……"
                cameraLauncher.launch(null)
            }
        }

        ssidText = TextView(this).apply {
            text = "SSID：未検出"
            textSize = 18f
            setPadding(0, 16, 0, 8)
        }

        val copySsidButton = Button(this).apply {
            text = "SSIDをコピー"
            setOnClickListener { copyText("SSID", ssid) }
        }

        passwordText = TextView(this).apply {
            text = "パスワード：未検出"
            textSize = 18f
            setPadding(0, 16, 0, 8)
        }

        val copyPasswordButton = Button(this).apply {
            text = "パスワードをコピー"
            setOnClickListener {
                copyText("パスワード", password)
            }
        }

        resultText = TextView(this).apply {
            text = "テレビのWi-Fi情報画面を撮影してください"
            textSize = 16f
            setPadding(0, 24, 0, 16)
        }

        layout.addView(title)
        layout.addView(captureButton)
        layout.addView(ssidText)
        layout.addView(copySsidButton)
        layout.addView(passwordText)
        layout.addView(copyPasswordButton)
        layout.addView(resultText)

        setContentView(scroll)
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

                // SSID：toyoko-inn + 数字4桁
                ssid = Regex(
                    """(?i)toyoko-inn\d{4}"""
                ).find(text)?.value ?: ""

                // パスワード：数字8桁
                password = Regex(
                    """(?<!\d)\d{8}(?!\d)"""
                ).find(text)?.value ?: ""

                ssidText.text =
                    if (ssid.isNotBlank()) "SSID：$ssid"
                    else "SSID：未検出"

                passwordText.text =
                    if (password.isNotBlank()) "パスワード：$password"
                    else "パスワード：未検出"

                resultText.text =
                    if (text.isBlank()) {
                        "文字を読み取れませんでした"
                    } else {
                        "読み取った文字：\n\n$text"
                    }
            }
            .addOnFailureListener { error ->
                resultText.text =
                    "読み取り失敗：${error.message}"
            }
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
