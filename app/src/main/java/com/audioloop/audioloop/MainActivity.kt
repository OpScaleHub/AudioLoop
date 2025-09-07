package com.audioloop.audioloop

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.audioloop.audioloop.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // TODO: Setup UI listeners for:
        // 1. Start/Stop button to launch/stop AudioLoopService
        // 2. App selection button to trigger MediaProjection screen capture prompt
        // 3. Volume sliders for internal audio and microphone
        // 4. Observe service status and update UI (status indicator, audio levels)
    }
}