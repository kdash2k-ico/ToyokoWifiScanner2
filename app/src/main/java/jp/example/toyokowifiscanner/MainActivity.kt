package jp.example.toyokowifiscanner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var resultText: TextView
    private lateinit var ssidText: TextView
    private lateinit var passwordText: TextView
    private lateinit var connectButton: Button
    private lateinit var cameraView: TextureView

    private var ssid = ""
    private var password = ""

    private var cameraDevice: android.hardware.camera2.CameraDevice? = null
    private var captureSession: android.hardware.camera2.CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraId = ""

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val analyzing = AtomicBoolean(false)
    private var scanStopped = false

    private val ocrExecutor = Executors.newSingleThreadExecutor()

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startCamera()
            } else {
                resultText.text = "カメラの使用を許可してください"
            }
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
            setPadding(0, 0, 0, 16)
        }
        root.addView(title)

        cameraView = TextureView(this).apply {
            visibility = View.VISIBLE
        }

        root.addView(
            cameraView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                600
            )
        )

        ssidText = TextView(this).apply {
            text = "SSID：読み取り中……"
            textSize = 18f
            setPadding(0, 16, 0, 8)
        }
        root.addView(ssidText)

        passwordText = TextView(this).apply {
            text = "パスワード：読み取り中……"
            textSize = 18f
            setPadding(0, 8, 0, 8)
        }
        root.addView(passwordText)

        connectButton = Button(this).apply {
            text = "Wi-Fiに接続"
            textSize = 18f
            visibility = View.GONE

            setOnClickListener {
                requestWifiConnection()
            }
        }
        root.addView(connectButton)

        val copySsidButton = Button(this).apply {
            text = "SSIDをコピー"
            setOnClickListener {
                copyText("SSID", ssid)
            }
        }
        root.addView(copySsidButton)

        val copyPasswordButton = Button(this).apply {
            text = "パスワードをコピー"
            setOnClickListener {
                copyText("パスワード", password)
            }
        }
        root.addView(copyPasswordButton)

        resultText = TextView(this).apply {
            text = "カメラを起動しています……"
            textSize = 16f
            setPadding(0, 16, 0, 16)
        }
        root.addView(resultText)

        setContentView(scroll)

        startCameraAfterPermission()
    }

    private fun startCameraAfterPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        if (scanStopped) return

        try {
            cameraThread =
                HandlerThread("WifiScannerCamera").also {
                    it.start()
                }

            cameraHandler =
                Handler(cameraThread!!.looper)

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
                    ) {
                    }

                    override fun onSurfaceTextureDestroyed(
                        surface: android.graphics.SurfaceTexture
                    ): Boolean {
                        return true
                    }

                    override fun onSurfaceTextureUpdated(
                        surface: android.graphics.SurfaceTexture
                    ) {
                        if (!scanStopped) {
                            analyzePreview()
                        }
                    }
                }

            if (cameraView.isAvailable) {
                openCamera()
            }

        } catch (e: Exception) {
            resultText.text =
                "カメラ起動エラー：${e.message}"
        }
    }

    private fun openCamera() {
        try {
            val manager =
                getSystemService(Context.CAMERA_SERVICE)
                        as android.hardware.camera2.CameraManager

            cameraId =
                manager.cameraIdList.firstOrNull {
                    manager.getCameraCharacteristics(it)
                        .get(
                            android.hardware.camera2.CameraCharacteristics
                                .LENS_FACING
                        ) ==
                            android.hardware.camera2.CameraCharacteristics
                                .LENS_FACING_BACK
                } ?: manager.cameraIdList.first()

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            manager.openCamera(
                cameraId,
                object :
                    android.hardware.camera2.CameraDevice.StateCallback() {

                    override fun onOpened(
                        camera: android.hardware.camera2.CameraDevice
                    ) {
                        cameraDevice = camera
                        showPreview()
                    }

                    override fun onDisconnected(
                        camera: android.hardware.camera2.CameraDevice
                    ) {
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(
                        camera: android.hardware.camera2.CameraDevice,
                        error: Int
                    ) {
                        camera.close()
                        cameraDevice = null

                        runOnUiThread {
                            resultText.text =
                                "カメラエラー：$error"
                        }
                    }
                },
                cameraHandler
            )

        } catch (e: Exception) {
            runOnUiThread {
                resultText.text =
                    "カメラ起動エラー：${e.message}"
            }
        }
    }

    private fun showPreview() {
        val texture =
            cameraView.surfaceTexture ?: return

        texture.setDefaultBufferSize(1280, 720)

        val surface = Surface(texture)

        try {
            val request =
                cameraDevice!!.createCaptureRequest(
                    android.hardware.camera2.CameraDevice
                        .TEMPLATE_PREVIEW
                ).apply {
                    addTarget(surface)
                }

            cameraDevice!!.createCaptureSession(
                listOf(surface),
                object :
                    android.hardware.camera2.CameraCaptureSession
                        .StateCallback() {

                    override fun onConfigured(
                        session: android.hardware.camera2.CameraCaptureSession
                    ) {
                        captureSession = session

                        session.setRepeatingRequest(
                            request.build(),
                            null,
                            cameraHandler
                        )

                        runOnUiThread {
                            resultText.text =
                                "テレビのWi-Fi情報をカメラに映してください"
                        }
                    }

                    override fun onConfigureFailed(
                        session: android.hardware.camera2.CameraCaptureSession
                    ) {
                        runOnUiThread {
                            resultText.text =
                                "カメラを準備できませんでした"
                        }
                    }
                },
                cameraHandler
            )

        } catch (e: Exception) {
            runOnUiThread {
                resultText.text =
                    "プレビューエラー：${e.message}"
            }
        }
    }

    private fun analyzePreview() {

        if (scanStopped) return

        if (!analyzing.compareAndSet(false, true)) {
            return
        }

        val bitmap =
            cameraView.bitmap ?: run {
                analyzing.set(false)
                return
            }

        ocrExecutor.execute {

            try {
                val image =
                    InputImage.fromBitmap(bitmap, 0)

                val recognizer =
                    TextRecognition.getClient(
                        TextRecognizerOptions.DEFAULT_OPTIONS
                    )

                recognizer.process(image)
                    .addOnSuccessListener { visionText ->

                        if (scanStopped) return@addOnSuccessListener

                        val text = visionText.text

                        val detectedSsid =
                            Regex(
                                """(?i)toyoko-inn\d{4}"""
                            ).find(text)?.value ?: ""

                        val detectedPassword =
                            Regex(
                                """(?<!\d)\d{8}(?!\d)"""
                            ).find(text)?.value ?: ""

                        if (
                            detectedSsid.isNotBlank() &&
                            detectedPassword.isNotBlank()
                        ) {
                            ssid = detectedSsid
                            password = detectedPassword

                            finishScanning()
                        }
                    }
                    .addOnFailureListener {
package jp.example.toyokowifiscanner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var resultText: TextView
    private lateinit var ssidText: TextView
    private lateinit var passwordText: TextView
    private lateinit var connectButton: Button
    private lateinit var cameraView: TextureView

    private var ssid = ""
    private var password = ""

    private var cameraDevice: android.hardware.camera2.CameraDevice? = null
    private var captureSession: android.hardware.camera2.CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraId = ""

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val analyzing = AtomicBoolean(false)
    private var scanStopped = false

    private val ocrExecutor = Executors.newSingleThreadExecutor()

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startCamera()
            } else {
                resultText.text = "カメラの使用を許可してください"
            }
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
            setPadding(0, 0, 0, 16)
        }
        root.addView(title)

        cameraView = TextureView(this).apply {
            visibility = View.VISIBLE
        }

        root.addView(
            cameraView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                600
            )
        )

        ssidText = TextView(this).apply {
            text = "SSID：読み取り中……"
            textSize = 18f
            setPadding(0, 16, 0, 8)
        }
        root.addView(ssidText)

        passwordText = TextView(this).apply {
            text = "パスワード：読み取り中……"
            textSize = 18f
            setPadding(0, 8, 0, 8)
        }
        root.addView(passwordText)

        connectButton = Button(this).apply {
            text = "Wi-Fiに接続"
            textSize = 18f
            visibility = View.GONE

            setOnClickListener {
                requestWifiConnection()
            }
        }
        root.addView(connectButton)

        val copySsidButton = Button(this).apply {
            text = "SSIDをコピー"
            setOnClickListener {
                copyText("SSID", ssid)
            }
        }
        root.addView(copySsidButton)

        val copyPasswordButton = Button(this).apply {
            text = "パスワードをコピー"
            setOnClickListener {
                copyText("パスワード", password)
            }
        }
        root.addView(copyPasswordButton)

        resultText = TextView(this).apply {
            text = "カメラを起動しています……"
            textSize = 16f
            setPadding(0, 16, 0, 16)
        }
        root.addView(resultText)

        setContentView(scroll)

        startCameraAfterPermission()
    }

    private fun startCameraAfterPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        if (scanStopped) return

        try {
            cameraThread =
                HandlerThread("WifiScannerCamera").also {
                    it.start()
                }

            cameraHandler =
                Handler(cameraThread!!.looper)

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
                    ) {
                    }

                    override fun onSurfaceTextureDestroyed(
                        surface: android.graphics.SurfaceTexture
                    ): Boolean {
                        return true
                    }

                    override fun onSurfaceTextureUpdated(
                        surface: android.graphics.SurfaceTexture
                    ) {
                        if (!scanStopped) {
                            analyzePreview()
                        }
                    }
                }

            if (cameraView.isAvailable) {
                openCamera()
            }

        } catch (e: Exception) {
            resultText.text =
                "カメラ起動エラー：${e.message}"
        }
    }

    private fun openCamera() {
        try {
            val manager =
                getSystemService(Context.CAMERA_SERVICE)
                        as android.hardware.camera2.CameraManager

            cameraId =
                manager.cameraIdList.firstOrNull {
                    manager.getCameraCharacteristics(it)
                        .get(
                            android.hardware.camera2.CameraCharacteristics
                                .LENS_FACING
                        ) ==
                            android.hardware.camera2.CameraCharacteristics
                                .LENS_FACING_BACK
                } ?: manager.cameraIdList.first()

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            manager.openCamera(
                cameraId,
                object :
                    android.hardware.camera2.CameraDevice.StateCallback() {

                    override fun onOpened(
                        camera: android.hardware.camera2.CameraDevice
                    ) {
                        cameraDevice = camera
                        showPreview()
                    }

                    override fun onDisconnected(
                        camera: android.hardware.camera2.CameraDevice
                    ) {
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(
                        camera: android.hardware.camera2.CameraDevice,
                        error: Int
                    ) {
                        camera.close()
                        cameraDevice = null

                        runOnUiThread {
                            resultText.text =
                                "カメラエラー：$error"
                        }
                    }
                },
                cameraHandler
            )

        } catch (e: Exception) {
            runOnUiThread {
                resultText.text =
                    "カメラ起動エラー：${e.message}"
            }
        }
    }

    private fun showPreview() {
        val texture =
            cameraView.surfaceTexture ?: return

        texture.setDefaultBufferSize(1280, 720)

        val surface = Surface(texture)

        try {
            val request =
                cameraDevice!!.createCaptureRequest(
                    android.hardware.camera2.CameraDevice
                        .TEMPLATE_PREVIEW
                ).apply {
                    addTarget(surface)
                }

            cameraDevice!!.createCaptureSession(
                listOf(surface),
                object :
                    android.hardware.camera2.CameraCaptureSession
                        .StateCallback() {

                    override fun onConfigured(
                        session: android.hardware.camera2.CameraCaptureSession
                    ) {
                        captureSession = session

                        session.setRepeatingRequest(
                            request.build(),
                            null,
                            cameraHandler
                        )

                        runOnUiThread {
                            resultText.text =
                                "テレビのWi-Fi情報をカメラに映してください"
                        }
                    }

                    override fun onConfigureFailed(
                        session: android.hardware.camera2.CameraCaptureSession
                    ) {
                        runOnUiThread {
                            resultText.text =
                                "カメラを準備できませんでした"
                        }
                    }
                },
                cameraHandler
            )

        } catch (e: Exception) {
            runOnUiThread {
                resultText.text =
                    "プレビューエラー：${e.message}"
            }
        }
    }

    private fun analyzePreview() {

        if (scanStopped) return

        if (!analyzing.compareAndSet(false, true)) {
            return
        }

        val bitmap =
            cameraView.bitmap ?: run {
                analyzing.set(false)
                return
            }

        ocrExecutor.execute {

            try {
                val image =
                    InputImage.fromBitmap(bitmap, 0)

                val recognizer =
                    TextRecognition.getClient(
                        TextRecognizerOptions.DEFAULT_OPTIONS
                    )

                recognizer.process(image)
                    .addOnSuccessListener { visionText ->

                        if (scanStopped) return@addOnSuccessListener

                        val text = visionText.text

                        val detectedSsid =
                            Regex(
                                """(?i)toyoko-inn\d{4}"""
                            ).find(text)?.value ?: ""

                        val detectedPassword =
                            Regex(
                                """(?<!\d)\d{8}(?!\d)"""
                            ).find(text)?.value ?: ""

                        if (
                            detectedSsid.isNotBlank() &&
                            detectedPassword.isNotBlank()
                        ) {
                            ssid = detectedSsid
                            password = detectedPassword

                            finishScanning()
                        }
                    }
                    .addOnFailureListener {
                        // 一時的なOCR失敗は無視して、
                        // 次のフレームを解析する
                    }
                    .addOnCompleteListener {
                        bitmap.recycle()
                        analyzing.set(false)
                    }

            } catch (_: Exception) {
                bitmap.recycle()
                analyzing.set(false)
            }
        }
    }

    private fun finishScanning() {

        if (scanStopped) return

        scanStopped = true

        runOnUiThread {

            ssidText.text =
                "SSID：$ssid"

            passwordText.text =
                "パスワード：$password"

            resultText.text =
                "読み取り成功！「Wi-Fiに接続」を押してください。"

            connectButton.visibility =
                View.VISIBLE

            cameraView.visibility =
                View.GONE

            cameraDevice?.close()
            cameraDevice = null

            captureSession?.close()
            captureSession = null

            cameraThread?.quitSafely()
            cameraThread = null
            cameraHandler = null
        }
    }

    private fun requestWifiConnection() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            resultText.text =
                "このAndroidバージョンでは接続リクエスト方式に対応していません"
            return
        }

        if (
            ssid.isBlank() ||
            password.isBlank()
        ) {
            resultText.text =
                "先にWi-Fi情報を読み取ってください"
            return
        }

        try {

            val specifier =
                WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(password)
                    .build()

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

            networkCallback?.let {
                try {
                    connectivityManager
                        .unregisterNetworkCallback(it)
                } catch (_: Exception) {
                }
            }

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
                                "接続できませんでした。パスワードやWi-Fi方式を確認してください"
                        }
                    }
                }

            networkCallback = callback

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

        cameraDevice?.close()
        captureSession?.close()
        cameraThread?.quitSafely()

        ocrExecutor.shutdown()

        super.onDestroy()
    }
}
