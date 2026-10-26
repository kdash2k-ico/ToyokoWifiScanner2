package jp.example.toyokowifiscanner

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MainActivity : AppCompatActivity() {

    private lateinit var resultText: TextView

    private val cameraLauncher =
        registerForActivityResult(
            ActivityResultContracts.TakePicturePreview()
        ) { bitmap: Bitmap? ->

            if (bitmap == null) {
                resultText.text = "撮影がキャンセルされました"
                return@registerForActivityResult
            }

            readText(bitmap)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        resultText = findViewById(R.id.resultText)

        findViewById<Button>(R.id.captureButton).setOnClickListener {
            resultText.text = "カメラを起動しています……"
            cameraLauncher.launch(null)
        }
    }

    private fun readText(bitmap: Bitmap) {

        resultText.text = "Wi-Fi情報を読み取っています……"

        val image = InputImage.fromBitmap(bitmap, 0)

        val recognizer =
            TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS
            )

        recognizer.process(image)
            .addOnSuccessListener { visionText ->

                val text = visionText.text

                if (text.isBlank()) {
                    resultText.text =
                        "文字を読み取れませんでした。\n" +
                        "テレビのWi-Fi画面を近づけて、もう一度撮影してください。"
                } else {
                    resultText.text =
                        "読み取った文字：\n\n$text"
                }
            }
            .addOnFailureListener { error ->

                resultText.text =
                    "文字の読み取りに失敗しました。\n\n" +
                        error.message
            }
    }
}
