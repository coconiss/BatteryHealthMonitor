// ui/main/MainActivity.kt
package com.batteryhealth.monitor.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.batteryhealth.monitor.R
import com.batteryhealth.monitor.databinding.ActivityMainBinding
import com.batteryhealth.monitor.domain.model.ConfidenceLevel
import com.batteryhealth.monitor.ui.history.HistoryActivity
import com.batteryhealth.monitor.util.BatteryUtils
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupObservers()
        setupClickListeners()
        setupAutoMonitoringToggle()
        checkPermissions()
        checkChargeCounterSupport()

        viewModel.loadBatteryHealth()
        viewModel.loadDeviceSpec()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "배터리 Health"
    }

    private fun setupObservers() {
        // 배터리 Health 결과
        viewModel.batteryHealthResult.observe(this) { result ->
            if (result != null) {
                displayHealthResult(result)
            } else {
                showInsufficientDataMessage()
            }
        }

        // 기기 스펙
        viewModel.deviceSpec.observe(this) { spec ->
            binding.deviceModelText.text = "${spec.manufacturer} ${spec.deviceModel}"
            binding.designCapacityText.text = "${spec.designCapacity} mAh"
            binding.specSourceText.text = "출처: ${translateSource(spec.source)}"
            binding.specConfidenceText.text = "신뢰도: ${(spec.confidence * 100).toInt()}%"
        }

        // 현재 배터리 정보
        viewModel.currentBatteryInfo.observe(this) { info ->
            binding.batteryPercentageText.text = "${info.percentage}%"
            binding.temperatureText.text = "${String.format("%.1f", info.temperature)}°C"
            binding.voltageText.text = "${info.voltage} mV"
            binding.chargingStatusText.text = if (info.isCharging) {
                "충전 중 (${info.chargerType})"
            } else {
                "충전 안함"
            }
        }

        // 모니터링 상태
        viewModel.isMonitoring.observe(this) { isMonitoring ->
            // 자동 모니터링이므로 UI 업데이트만 수행
            Timber.d("Monitoring status: $isMonitoring")
        }

        // 로딩 상태
        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }

        // 에러 메시지
        viewModel.errorMessage.observe(this) { message ->
            message?.let {
                Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun setupClickListeners() {
        binding.refreshButton.setOnClickListener {
            viewModel.loadBatteryHealth()
            viewModel.refreshCurrentBatteryInfo()
        }

        binding.viewHistoryButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    private fun setupAutoMonitoringToggle() {
        val prefs = getSharedPreferences("battery_health_prefs", Context.MODE_PRIVATE)
        val isAutoEnabled = prefs.getBoolean("auto_monitoring_enabled", true)

        binding.autoMonitoringSwitch.isChecked = isAutoEnabled

        binding.autoMonitoringSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_monitoring_enabled", isChecked).apply()

            val message = if (isChecked) {
                "충전 시 자동으로 데이터를 수집합니다"
            } else {
                "자동 모니터링이 비활성화되었습니다"
            }
            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()

            // 현재 충전 중이고 자동 모니터링이 활성화되었다면 즉시 시작
            if (isChecked) {
                val batteryInfo = viewModel.currentBatteryInfo.value
                if (batteryInfo?.isCharging == true) {
                    viewModel.startMonitoring()
                }
            }
        }
    }

    private fun displayHealthResult(result: com.batteryhealth.monitor.domain.model.BatteryHealthResult) {
        binding.apply {
            healthResultCard.visibility = android.view.View.VISIBLE
            insufficientDataCard.visibility = android.view.View.GONE

            // Health 퍼센트
            healthPercentageText.text = "${result.healthPercentage.toInt()}%"
            healthProgressBar.progress = result.healthPercentage.toInt()

            // 색상 설정
            val color = getHealthColor(result.healthPercentage)
            healthProgressBar.progressTintList = android.content.res.ColorStateList.valueOf(color)
            healthPercentageText.setTextColor(color)

            // 상태 메시지
            healthStatusText.text = getHealthStatusMessage(result.healthPercentage)

            // 용량 정보
            capacityInfoText.text = getString(
                R.string.capacity_format,
                result.estimatedCurrentCapacity,
                result.designCapacity
            )

            // 신뢰도
            confidenceLevelText.text = result.confidenceLevel.getDescription()
            confidenceLevelText.setTextColor(
                ContextCompat.getColor(this@MainActivity, result.confidenceLevel.getColorResource())
            )

            // 세션 정보
            sessionCountText.text = getString(
                R.string.session_count_format,
                result.validSessionsCount,
                result.totalSessionsCount
            )

            // 신뢰도가 낮을 때 경고
            if (result.confidenceLevel == ConfidenceLevel.VERY_LOW ||
                result.confidenceLevel == ConfidenceLevel.LOW) {
                lowConfidenceWarning.visibility = android.view.View.VISIBLE
            } else {
                lowConfidenceWarning.visibility = android.view.View.GONE
            }
        }
    }

    private fun showInsufficientDataMessage() {
        binding.healthResultCard.visibility = android.view.View.GONE
        binding.insufficientDataCard.visibility = android.view.View.VISIBLE
    }

    private fun getHealthColor(percentage: Float): Int {
        return when {
            percentage >= 90 -> ContextCompat.getColor(this, R.color.health_excellent)
            percentage >= 80 -> ContextCompat.getColor(this, R.color.health_good)
            percentage >= 70 -> ContextCompat.getColor(this, R.color.health_fair)
            else -> ContextCompat.getColor(this, R.color.health_poor)
        }
    }

    private fun getHealthStatusMessage(percentage: Float): String {
        return when {
            percentage >= 90 -> "배터리 상태가 매우 좋습니다"
            percentage >= 80 -> "배터리 상태가 양호합니다"
            percentage >= 70 -> "배터리 노화가 진행 중입니다"
            percentage >= 60 -> "배터리 교체를 고려하세요"
            else -> "배터리 교체가 권장됩니다"
        }
    }

    private fun translateSource(source: String): String {
        return when {
            source.startsWith("power_profile") -> "시스템 (PowerProfile)"
            source.startsWith("embedded_database") -> "내장 데이터베이스"
            source.startsWith("online_api") -> "온라인 API"
            source.startsWith("crowdsourced") -> "크라우드소싱"
            source.startsWith("system_property") -> "시스템 정보"
            source.startsWith("estimated") -> "추정값"
            else -> source
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun checkChargeCounterSupport() {
        if (!BatteryUtils.isChargeCounterSupported(this)) {
            showChargeCounterNotSupportedDialog()
        }
    }

    private fun showChargeCounterNotSupportedDialog() {
        AlertDialog.Builder(this)
            .setTitle("기기 미지원")
            .setMessage(R.string.charge_counter_not_supported)
            .setPositiveButton("확인") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("권한 필요")
            .setMessage("배터리 모니터링 알림을 표시하려면 알림 권한이 필요합니다.")
            .setPositiveButton("확인") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showSettingsDialog()
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            R.id.action_clear_data -> {
                showClearDataDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("battery_health_prefs", Context.MODE_PRIVATE)
        val isAutoEnabled = prefs.getBoolean("auto_monitoring_enabled", true)

        AlertDialog.Builder(this)
            .setTitle("설정")
            .setSingleChoiceItems(
                arrayOf("자동 모니터링 활성화"),
                if (isAutoEnabled) 0 else -1
            ) { dialog, which ->
                val newState = which == 0
                prefs.edit().putBoolean("auto_monitoring_enabled", newState).apply()
                binding.autoMonitoringSwitch.isChecked = newState

                val message = if (newState) {
                    "자동 모니터링이 활성화되었습니다"
                } else {
                    "자동 모니터링이 비활성화되었습니다"
                }
                Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("취소") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("앱 정보")
            .setMessage("""
                배터리 Health 모니터
                버전 1.0.0
                
                이 앱은 충전 데이터를 분석하여 배터리의 건강 상태를 추정합니다.
                
                주요 기능:
                • 자동 배터리 모니터링
                • 충전 기록 추적
                • 배터리 건강도 계산
                • 상세 충전 통계
                
                ⚠️ 주의: 제공되는 수치는 추정값이며 공식 측정값이 아닙니다.
            """.trimIndent())
            .setPositiveButton("확인") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showClearDataDialog() {
        AlertDialog.Builder(this)
            .setTitle("데이터 삭제")
            .setMessage("모든 충전 기록을 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.")
            .setPositiveButton("삭제") { _, _ ->
                lifecycleScope.launch {
                    viewModel.clearAllData()
                    Snackbar.make(binding.root, "모든 데이터가 삭제되었습니다", Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        // 화면이 다시 보일 때 배터리 정보 갱신
        viewModel.refreshCurrentBatteryInfo()
    }

    override fun onDestroy() {
        super.onDestroy()
        // ViewModel에서 실시간 업데이트 중지
        viewModel.stopRealTimeUpdates()
    }
}