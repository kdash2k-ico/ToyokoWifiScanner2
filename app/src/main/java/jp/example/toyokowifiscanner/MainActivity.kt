package jp.example.toyokowifiscanner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var resultText: TextView
    private lateinit var ssidText: TextView
    private lateinit var passwordText: TextView
    private lateinit var connectButton: Button

    private var ssid = ""
    private var password = ""

    private lateinit var photoFile: File
    private lateinit var photoUri: Uri

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /*
     * カメラ撮影
     *
     * 撮影後に確認画面を表示せず、
     * そのまま readPhoto() に進む。
     */
    private val cameraLauncher =
        registerForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { success ->

            if (success) {
                readPhoto()
            } else {
                resultText.text = "撮影がキャンセルされました"
            }
        }

    /*
     * カメラ権限
     */
    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                launchCamera()
            } else {
                resultText.text = "カメラの使用を許可してください"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()

        createScreen()
    }

    /*
     * 画面を作成
     */
    private fun createScreen() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val scrollView = ScrollView(this)
        scrollView.addView(root)

        /*
         * タイトル
         */
        val title = TextView(this).apply {
            text = "東横INN Wi-Fiスキャナー"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        root.addView(title)

        /*
         * 撮影ボタン
         */
        val captureButton = Button(this).apply {
            text = "📷 撮影して読み取る"
            textSize = 18f

            setOnClickListener {

                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                ) {

                    launchCamera()

                } else {

                    cameraPermissionLauncher.launch(
                        Manifest.permission.CAMERA
                    )
                }
            }
        }

        root.addView(captureButton)

        /*
         * SSID
         */
        ssidText = TextView(this).apply {
            text = "SSID：未検出"
            textSize = 18f
            setPadding(0, 24, 0, 8)
        }

        root.addView(ssidText)

        /*
         * SSIDコピー
         */
        val copySsidButton = Button(this).apply {
            text = "SSIDをコピー"

            setOnClickListener {
                copyText("SSID", ssid)
            }
        }

        root.addView(copySsidButton)

        /*
         * パスワード
         */
        passwordText = TextView(this).apply {
            text = "パスワード：未検出"
            textSize = 18f
            setPadding(0, 24, 0, 8)
        }

        root.addView(passwordText)

        /*
         * パスワードコピー
         */
        val copyPasswordButton = Button(this).apply {
            text = "パスワードをコピー"

            setOnClickListener {
                copyText("パスワード", password)
            }
        }

        root.addView(copyPasswordButton)

        /*
         * Wi-Fi接続ボタン
         *
         * OCR成功時だけ表示する。
         */
        connectButton = Button(this).apply {
            text = "📶 Wi-Fiに接続"
            textSize = 18f
            visibility = View.GONE

            setOnClickListener {
                requestWifiConnection()
            }
        }

        root.addView(connectButton)

        /*
         * 結果表示
         */
        resultText = TextView(this).apply {
            text = "テレビのWi-Fi情報画面を撮影してください"
            textSize = 16f
            setPadding(0, 24, 0, 16)
        }

        root.addView(resultText)

        setContentView(scrollView)
    }

    /*
     * カメラを起動
     */
    private fun launchCamera() {

        try {

            photoFile = File.createTempFile(
                "wifi_photo_",
                ".jpg",
                cacheDir
            )

            photoUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                photoFile
            )

            resultText.text = "カメラを起動しています……"

            cameraLauncher.launch(photoUri)

        } catch (e: Exception) {

            resultText.text =
                "カメラ起動エラー：${e.message}"
        }
    }

    /*
     * 撮影した写真をOCR
     */
    private fun readPhoto() {

        resultText.text =
            "写真を読み取っています……"

        val bitmap =
            BitmapFactory.decodeFile(
                photoFile.absolutePath
            )

        if (bitmap == null) {

            resultText.text =
                "写真を開けませんでした"

            return
        }

        /*
         * 高解像度写真をそのままML Kitへ渡す
         */
        val image =
            InputImage.fromBitmap(
                bitmap,
                0
            )

        val recognizer =
            TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS
            )

        recognizer.process(image)

            .addOnSuccessListener { visionText ->

                val text = visionText.text

                /*
                 * 東横INNのSSID
                 *
                 * 例：
                 * toyoko-inn1234
                 */
                ssid =
                    Regex(
                        """(?i)toyoko-inn\d{4}"""
                    )
                        .find(text)
                        ?.value
                        ?: ""

                /*
                 * 8桁の数字をパスワードとして取得
                 */
                password =
                    Regex(
                        """(?<!\d)\d{8}(?!\d)"""
                    )
                        .find(text)
                        ?.value
                        ?: ""

                /*
                 * SSID表示
                 */
                ssidText.text =
                    if (ssid.isNotBlank()) {

                        "SSID：$ssid"

                    } else {

                        "SSID：未検出"
                    }

                /*
                 * パスワード表示
                 */
                passwordText.text =
                    if (password.isNotBlank()) {

                        "パスワード：$password"

                    } else {

                        "パスワード：未検出"
                    }

                /*
                 * 両方取得できたら
                 * Wi-Fi接続ボタンを表示
                 */
                if (
                    ssid.isNotBlank() &&
                    password.isNotBlank()
                ) {

                    connectButton.visibility =
                        View.VISIBLE

                    resultText.text =
                        "読み取り成功！\n\n「Wi-Fiに接続」を押してください。"

                } else {

                    connectButton.visibility =
                        View.GONE

                    resultText.text =
                        if (text.isBlank()) {

                            "文字を読み取れませんでした。\nもう一度撮影してください。"

                        } else {

                            "SSIDまたはパスワードを読み取れませんでした。\n\n読み取った文字：\n$text"
                        }
                }
            }

            .addOnFailureListener { error ->

                resultText.text =
                    "読み取り失敗：${error.message}"
            }

            .addOnCompleteListener {

                /*
                 * OCR終了後にBitmapを解放
                 */
                bitmap.recycle()

                /*
                 * 一時ファイル削除
                 */
                photoFile.delete()
            }
    }

    /*
     * AndroidのWi-Fi接続確認画面を呼び出す
     */
    private fun requestWifiConnection() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {

            resultText.text =
                "このAndroidバージョンでは、この接続方式に対応していません"

            return
        }

        if (
            ssid.isBlank() ||
            password.isBlank()
        ) {

            resultText.text =
                "先にSSIDとパスワードを読み取ってください"

            return
        }

        try {

            /*
             * WPA2 Wi-Fiとして接続要求
             */
            val specifier =
                WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(password)
                    .build()

            /*
             * Wi-Fiネットワークを要求
             */
            val request =
                NetworkRequest.Builder()
                    .addTransportType(
                        NetworkCapabilities.TRANSPORT_WIFI
                    )
                    .setNetworkSpecifier(specifier)
                    .build()

            val connectivityManager =
                getSystemService(
                    Context.CONNECTIVITY_SERVICE
                ) as ConnectivityManager

            /*
             * 前回の接続要求が残っていたら解除
             */
            networkCallback?.let {

                try {

                    connectivityManager
                        .unregisterNetworkCallback(it)

                } catch (_: Exception) {
                }
            }

            /*
             * 接続結果
             */
            val callback =
                object :
                    ConnectivityManager.NetworkCallback() {

                    override fun onAvailable(
                        network: Network
                    ) {

                        runOnUiThread {

                            resultText.text =
                                "Wi-Fiネットワークに接続しました"
                        }
                    }

                    override fun onUnavailable() {

                        runOnUiThread {

                            resultText.text =
                                "接続できませんでした。\n\nパスワードやWi-Fi方式を確認してください。"
                        }
                    }

                    override fun onLost(
                        network: Network
                    ) {

                        runOnUiThread {

                            resultText.text =
                                "Wi-Fi接続が切断されました"
                        }
                    }
                }

            networkCallback = callback

            /*
             * Androidへ接続要求
             */
            connectivityManager.requestNetwork(
                request,
                callback
            )

            resultText.text =
                "Androidの確認画面で接続を許可してください"

        } catch (e: Exception) {

            resultText.text =
                "接続リクエストエラー：${e.message}"
        }
    }

    /*
     * 文字列をクリップボードへコピー
     */
    private fun copyText(
        label: String,
        value: String
    ) {

        if (value.isBlank()) {

            resultText.text =
                "$label が見つかっていません"

            return
        }

        val clipboard =
            getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                label,
                value
            )
        )

        resultText.text =
            "$label をコピーしました"
    }

    override fun onDestroy() {

        /*
         * Wi-Fi接続要求を解除
         */
        networkCallback?.let {

            try {

                val connectivityManager =
                    getSystemService(
                        Context.CONNECTIVITY_SERVICE
                    ) as ConnectivityManager

                connectivityManager
                    .unregisterNetworkCallback(it)

            } catch (_: Exception) {
            }
        }

        super.onDestroy()
    }
}
