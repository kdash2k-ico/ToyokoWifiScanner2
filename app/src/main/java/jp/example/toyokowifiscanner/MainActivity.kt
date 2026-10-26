package jp.example.toyokowifiscanner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiNetworkSpecifier
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var resultText: TextView
    private lateinit var ssidText: TextView
    private lateinit var passwordText: TextView
    private lateinit var connectButton: Button
    private lateinit var cameraView: TextureView

    private var ssid = ""
    private var password = ""
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraId: String = ""
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) startCamera()
            else resultText.text = "カメラの使用を許可してください"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val scroll = ScrollView(this)
        scroll.addView(root)

        val title = TextView(this).apply {
            text = "東横INN Wi-Fiスキャナー"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }
        root.addView(title)

        cameraView = TextureView(this).apply {
            visibility = View.GONE
        }
        root.addView(
            cameraView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 600
            )
        )

        val captureButton = Button(this).apply {
            text = "撮影して読み取る"
            textSize = 18f
            setOnClickListener {
                showCamera()
            }
        }
        root.addView(captureButton)

        ssidText = TextView(this).apply {
            text = "SSID：未検出"
            textSize = 18f
            setPadding(0, 16, 0, 8)
        }
        root.addView(ssidText)

        val copySsidButton = Button(this).apply {
            text = "SSIDをコピー"
            setOnClickListener { copyText("SSID", ssid) }
        }
        root.addView(copySsidButton)

        passwordText = TextView(this).apply {
            text = "パスワード：未検出"
            textSize = 18f
            setPadding(0, 16, 0, 8)
        }
        root.addView(passwordText)

        val copyPasswordButton = Button(this).apply {
            text = "パスワードをコピー"
            setOnClickListener { copyText("パスワード", password) }
        }
        root.addView(copyPasswordButton)

        connectButton = Button(this).apply {
            text = "Wi-Fiに接続"
            textSize = 18f
            visibility = View.GONE
            setOnClickListener { requestWifiConnection() }
        }
        root.addView(connectButton)

        resultText = TextView(this).apply {
            text = "テレビのWi-Fi情報画面を撮影してください"
            textSize = 16f
            setPadding(0, 24, 0, 16)
        }
        root.addView(resultText)

        setContentView(scroll)
    }

    private fun showCamera() {
        cameraView.visibility = View.VISIBLE
        resultText.text = "撮影画面を準備しています……"
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        try {
            cameraThread = HandlerThread("WifiScannerCamera").also { it.start() }
            cameraHandler = Handler(cameraThread!!.looper)

            cameraView.surfaceTextureListener =
                object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: android.graphics.SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        openCamera()
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: android.graphics.SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {}

                    override fun onSurfaceTextureDestroyed(
                        surface: android.graphics.SurfaceTexture
                    ): Boolean = true

                    override fun onSurfaceTextureUpdated(
                        surface: android.graphics.SurfaceTexture
                    ) {}
                }

            if (cameraView.isAvailable) openCamera()
        } catch (e: Exception) {
            resultText.text = "カメラ起動エラー：${e.message}"
        }
    }

    private fun openCamera() {
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraId = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.first()

            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) return

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    showPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    runOnUiThread {
                        resultText.text = "カメラエラー：$error"
                    }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            runOnUiThread {
                resultText.text = "カメラ起動エラー：${e.message}"
            }
        }
    }

    private fun showPreview() {
        val texture = cameraView.surfaceTexture ?: return
        texture.setDefaultBufferSize(1280, 720)
        val surface = Surface(texture)

        try {
            val request = cameraDevice!!.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            ).apply {
                addTarget(surface)
            }

            cameraDevice!!.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        session.setRepeatingRequest(
                            request.build(), null, cameraHandler
                        )
                        runOnUiThread {
                            resultText.text = "文字がはっきり見えたら、画面をタップして撮影"
                            cameraView.setOnClickListener { takePhoto() }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        runOnUiThread {
                            resultText.text = "カメラを準備できませんでした"
                        }
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            runOnUiThread {
                resultText.text = "プレビューエラー：${e.message}"
            }
        }
    }

    private fun takePhoto() {
        val bitmap = cameraView.bitmap
        if (bitmap == null) {
            resultText.text = "撮影できませんでした。もう一度お試しください"
            return
        }

        cameraView.setOnClickListener(null)
        cameraView.visibility = View.GONE
        cameraDevice?.close()
        cameraDevice = null
        captureSession?.close()
        captureSession = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null

        resultText.text = "写真を読み取っています……"
        readBitmap(bitmap)
    }

    private fun readBitmap(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                ssid = Regex("""(?i)toyoko-inn\d{4}""")
                    .find(text)?.value ?: ""
                password = Regex("""(?<!\d)\d{8}(?!\d)""")
                    .find(text)?.value ?: ""

                ssidText.text =
                    if (ssid.isNotBlank()) "SSID：$ssid" else "SSID：未検出"
                passwordText.text =
                    if (password.isNotBlank()) "パスワード：$password"
                    else "パスワード：未検出"

                connectButton.visibility =
                    if (ssid.isNotBlank() && password.isNotBlank())
                        View.VISIBLE else View.GONE

                resultText.text = when {
                    ssid.isBlank() || password.isBlank() ->
                        "SSIDまたはパスワードを読み取れませんでした。もう一度撮影してください。\n\n$text"
                    else -> "読み取り成功！「Wi-Fiに接続」を押してください。"
                }
            }
            .addOnFailureListener { error ->
                resultText.text = "読み取り失敗：${error.message}"
            }
            .addOnCompleteListener {
                bitmap.recycle()
            }
    }

    private fun requestWifiConnection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            resultText.text = "このAndroidバージョンでは接続リクエスト方式に対応していません"
            return
        }

        if (ssid.isBlank() || password.isBlank()) {
            resultText.text = "先にSSIDとパスワードを読み取ってください"
            return
        }

        try {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()

            val connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            networkCallback?.let {
                try {
                    connectivityManager.unregisterNetworkCallback(it)
                } catch (_: Exception) {}
            }

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runOnUiThread {
                        resultText.text = "Wi-Fiネットワークに接続しました"
                    }
                }

                override fun onUnavailable() {
                    runOnUiThread {
                        resultText.text = "接続できませんでした。パスワードやWi-Fi方式を確認してください"
                    }
                }
            }

            networkCallback = callback
            connectivityManager.requestNetwork(request, callback)
            resultText.text = "Androidの確認画面で接続を許可してください"
        } catch (e: Exception) {
            resultText.text = "接続リクエストエラー：${e.message}"
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
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        resultText.text = "$label をコピーしました"
    }

    override fun onDestroy() {
        cameraDevice?.close()
        captureSession?.close()
        cameraThread?.quitSafely()
        super.onDestroy()
    }
}
