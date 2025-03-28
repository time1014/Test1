package com.example.han

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var progressBar: ProgressBar
    private lateinit var player: ExoPlayer
    private lateinit var interpreter: Interpreter


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // findViewById로 뷰 초기화
        playerView = findViewById(R.id.player_view)
        progressBar = findViewById(R.id.progressbar)


        // 시스템 바 핸들링
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // ExoPlayer 초기화 (RTSP 스트림 재생)
        exoPlayerInit()

        // TensorFlow Lite 모델 로드
        loadModel()
    }

    @OptIn(UnstableApi::class)  // Add this annotation to opt-in to the unstable API
    private fun exoPlayerInit() {
        // RTSP 스트림 URL
        val uri = "rtsp://192.168.0.115:8080/h264_pcm.sdp".toUri()

        // ExoPlayer 초기화
        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        // RTSP 미디어 소스 생성
        val mediaItem = MediaItem.fromUri(uri)
        val rtspMediaSource = RtspMediaSource.Factory()
            .createMediaSource(mediaItem)

        // ExoPlayer에 미디어 소스 설정
        player.setMediaSource(rtspMediaSource)
        player.prepare()
        player.playWhenReady = true

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                // 재생 상태에 따라 ProgressBar visibility 설정
                progressBar.visibility = if (isPlaying) View.GONE else View.VISIBLE

                // 스트리밍이 시작된 후 3초 후에 스크린샷 찍기
                if (isPlaying) {
                    Handler().postDelayed({
                        takeScreenshotFromPlayerView { bitmap ->
                            if (bitmap != null) {
                                Toast.makeText(this@MainActivity, "Screenshot captured!", Toast.LENGTH_SHORT).show()
                                // TensorFlow Lite로 고양이 인식
                                recognizeCat(bitmap)
                            } else {
                                Toast.makeText(this@MainActivity, "Screenshot failed.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }, 3000) // 3초 후에 스크린샷 찍기
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                // 에러 처리 로직 추가
            }
        })
    }


    private fun loadModel() {
        try {
            val modelFile = assets.openFd("model.tflite")
            val fileInputStream = FileInputStream(modelFile.fileDescriptor)
            val fileChannel = fileInputStream.channel
            val byteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, modelFile.startOffset, modelFile.declaredLength)

            interpreter = Interpreter(byteBuffer)
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "모델 파일 로딩에 실패했습니다.", Toast.LENGTH_SHORT).show()
        }
    }
    // 고양이 인식
    private fun recognizeCat(bitmap: Bitmap) {
        // 모델의 예상 입력 크기 (예: 224x224 크기의 이미지 -> 307200 바이트)
        val inputSize = 224
        val modelInputSize = 307200  // 예시로 설정한 입력 크기 (224x224x3 이미지 크기)
        val outputSize = 400  // 모델의 출력 크기

        // 이미지를 224x224 크기로 리사이즈
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        // 이미지를 TensorImage로 변환
        val inputImage = TensorImage.fromBitmap(resizedBitmap)

        // ByteBuffer의 크기를 모델의 예상 크기에 맞게 설정
        val byteBuffer = ByteBuffer.allocateDirect(modelInputSize)
        byteBuffer.order(ByteOrder.nativeOrder())

        // ByteBuffer에 데이터를 넣기 전에 남은 공간을 확인합니다.
        if (byteBuffer.remaining() >= inputImage.buffer.remaining()) {
            // 이미지 데이터를 ByteBuffer에 로드
            byteBuffer.put(inputImage.buffer)
        } else {
            // 공간이 부족하면 에러 처리 또는 로그 출력
            Log.e("BufferError", "ByteBuffer has insufficient space!")
            return
        }

        // 모델 실행
        val outputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, outputSize), DataType.FLOAT32)  // 400 크기 출력 텐서

        // 모델 추론 실행
        interpreter.run(byteBuffer, outputBuffer.buffer.rewind())

        // 추론 결과 처리
        val result = outputBuffer.floatArray
        val isCat = result[0] > result[1]  // 예시로 첫 번째 인덱스가 고양이로 판단되면
        if (isCat) {
            Toast.makeText(this, "고양이가 인식되었습니다!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "고양이가 인식되지 않았습니다.", Toast.LENGTH_SHORT).show()
        }
    }



    @OptIn(UnstableApi::class)
    private fun takeScreenshotFromPlayerView(callback: (Bitmap?) -> Unit) {
        // PlayerView의 videoSurfaceView를 SurfaceView로 캐스팅
        val videoSurfaceView = playerView.videoSurfaceView as? SurfaceView
        if (videoSurfaceView == null || videoSurfaceView.width <= 0 || videoSurfaceView.height <= 0) {
            callback(null)
            return
        }

        // 캡처할 Bitmap 생성 (SurfaceView 크기와 동일)
        val bitmap = Bitmap.createBitmap(
            videoSurfaceView.width,
            videoSurfaceView.height,
            Bitmap.Config.ARGB_8888
        )

        try {
            // PixelCopy 요청을 위한 HandlerThread 생성
            val handlerThread = HandlerThread("PixelCopier")
            handlerThread.start()
            PixelCopy.request(
                videoSurfaceView,
                bitmap,
                { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        callback(bitmap)
                    } else {
                        callback(null)
                    }
                    handlerThread.quitSafely()
                },
                Handler(handlerThread.looper)
            )
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            callback(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
        interpreter.close() // 모델 해제
    }
}
