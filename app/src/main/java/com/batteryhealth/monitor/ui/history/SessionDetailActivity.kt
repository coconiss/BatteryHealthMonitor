// ui/history/SessionDetailActivity.kt (개선 버전)
package com.batteryhealth.monitor.ui.history

import android.os.Bundle
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.batteryhealth.monitor.R
import com.batteryhealth.monitor.data.local.entity.BatteryMeasurement
import com.batteryhealth.monitor.data.local.entity.ChargingSession
import com.batteryhealth.monitor.databinding.ActivitySessionDetailBinding
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class SessionDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionDetailBinding
    private val viewModel: SessionDetailViewModel by viewModels()

    private val dateFormat = SimpleDateFormat("yyyy년 M월 d일 HH:mm:ss", Locale.KOREAN)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREAN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (sessionId == -1L) {
            Timber.e("Invalid session ID")
            finish()
            return
        }

        setupToolbar()
        setupObservers()

        viewModel.loadSessionDetail(sessionId)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "충전 세션 상세"
            setDisplayHomeAsUpEnabled(true)
        }
    }

    private fun setupObservers() {
        viewModel.session.observe(this) { session ->
            session?.let {
                displaySessionInfo(it)
                Timber.d("Session loaded: ${it.id}, measurements expected")
            }
        }

        viewModel.measurements.observe(this) { measurements ->
            Timber.d("Measurements received: ${measurements.size} items")

            if (measurements.isNotEmpty()) {
                displayCharts(measurements)
                displayStatistics(measurements)
                displayMeasurementsList(measurements)
            } else {
                Timber.w("No measurements found for this session")
                showNoMeasurementsMessage()
            }
        }
    }

    private fun displaySessionInfo(session: ChargingSession) {
        binding.apply {
            // 기본 정보
            sessionDateText.text = dateFormat.format(Date(session.startTimestamp))

            // 시작 시간
            startTimeText.text = timeFormat.format(Date(session.startTimestamp))

            // 종료 시간 및 충전 시간
            session.endTimestamp?.let { endTime ->
                endTimeText.text = timeFormat.format(Date(endTime))

                val duration = endTime - session.startTimestamp
                val hours = TimeUnit.MILLISECONDS.toHours(duration)
                val minutes = TimeUnit.MILLISECONDS.toMinutes(duration) % 60
                val seconds = TimeUnit.MILLISECONDS.toSeconds(duration) % 60
                durationText.text = String.format(
                    Locale.getDefault(),
                    "%d시간 %d분 %d초",
                    hours, minutes, seconds
                )

                // 충전 속도 계산 (mAh/hour)
                session.estimatedCapacity?.let { capacity ->
                    val durationHours = duration / 3600000.0 // ms to hours
                    if (durationHours > 0) {
                        val chargingRate = capacity / durationHours
                        chargingRateText.text = String.format(
                            Locale.getDefault(),
                            "%.0f mAh/h",
                            chargingRate
                        )
                    }
                }
            } ?: run {
                endTimeText.text = "진행 중"
                durationText.text = "측정 중"
            }

            // 배터리 변화
            val endPercentage = session.endPercentage ?: session.startPercentage
            val chargeChange = endPercentage - session.startPercentage
            batteryChangeText.text = String.format(
                Locale.getDefault(),
                "%d%% → %d%% (+%d%%)",
                session.startPercentage,
                endPercentage,
                chargeChange
            )

            // 추정 용량
            session.estimatedCapacity?.let {
                estimatedCapacityText.text = String.format(
                    Locale.getDefault(),
                    "%,d mAh",
                    it
                )
                capacityLayout.visibility = android.view.View.VISIBLE
            } ?: run {
                capacityLayout.visibility = android.view.View.GONE
            }

            // Charge Counter 정보
            if (session.startChargeCounter != null && session.endChargeCounter != null) {
                chargeCounterLayout.visibility = android.view.View.VISIBLE
                startChargeCounterText.text = String.format(
                    Locale.getDefault(),
                    "%,d µAh",
                    session.startChargeCounter
                )
                endChargeCounterText.text = String.format(
                    Locale.getDefault(),
                    "%,d µAh",
                    session.endChargeCounter ?: 0L
                )

                val chargeAdded = (session.endChargeCounter ?: 0L) - session.startChargeCounter
                chargeAddedText.text = String.format(
                    Locale.getDefault(),
                    "%,d µAh (%,d mAh)",
                    chargeAdded,
                    chargeAdded / 1000
                )
            } else {
                chargeCounterLayout.visibility = android.view.View.GONE
            }

            // 온도 정보
            avgTemperatureText.text = String.format(
                Locale.getDefault(),
                "%.1f°C",
                session.averageTemperature
            )
            maxTemperatureText.text = String.format(
                Locale.getDefault(),
                "%.1f°C",
                session.maxTemperature
            )

            // 온도 경고
            if (session.maxTemperature > 50f) {
                temperatureWarning.visibility = android.view.View.VISIBLE
                temperatureWarningText.text = String.format(
                    Locale.getDefault(),
                    "⚠️ 최대 온도가 %.1f°C로 높습니다",
                    session.maxTemperature
                )
            } else {
                temperatureWarning.visibility = android.view.View.GONE
            }

            // 전압 정보
            avgVoltageText.text = String.format(
                Locale.getDefault(),
                "%,d mV",
                session.averageVoltage
            )

            // 충전기 타입
            session.chargerType?.let {
                chargerTypeText.text = when(it) {
                    "AC" -> "AC 어댑터"
                    "USB" -> "USB 충전"
                    "Wireless" -> "무선 충전"
                    "DISCHARGE" -> "방전 (모니터링)"
                    else -> it
                }
                chargerTypeLayout.visibility = android.view.View.VISIBLE
            } ?: run {
                chargerTypeLayout.visibility = android.view.View.GONE
            }

            // 충전 속도
            session.chargingSpeed?.let {
                chargingSpeedText.text = it
                chargingSpeedLayout.visibility = android.view.View.VISIBLE
            } ?: run {
                chargingSpeedLayout.visibility = android.view.View.GONE
            }

            // 유효성 상태
            if (session.isValid) {
                statusText.text = "✓ 유효한 데이터"
                statusText.setTextColor(
                    ContextCompat.getColor(this@SessionDetailActivity, R.color.health_good)
                )
                invalidReasonLayout.visibility = android.view.View.GONE
            } else {
                statusText.text = "✗ 무효한 데이터"
                statusText.setTextColor(
                    ContextCompat.getColor(this@SessionDetailActivity, R.color.health_poor)
                )
                invalidReasonLayout.visibility = android.view.View.VISIBLE
                invalidReasonText.text = session.invalidReason ?: "알 수 없는 이유"
            }
        }
    }

    private fun displayCharts(measurements: List<BatteryMeasurement>) {
        if (measurements.isEmpty()) {
            Timber.w("No measurements to display in charts")
            return
        }

        val startTime = measurements.first().timestamp

        // 배터리 퍼센트 차트
        setupBatteryPercentChart(measurements, startTime)

        // 온도 차트
        setupTemperatureChart(measurements, startTime)

        // 전압 차트
        setupVoltageChart(measurements, startTime)

        // Charge Counter 차트 (데이터가 있는 경우)
        val hasChargeCounter = measurements.any { it.chargeCounter != null }
        if (hasChargeCounter) {
            binding.chargeCounterChartCard.visibility = android.view.View.VISIBLE
            setupChargeCounterChart(measurements, startTime)
        } else {
            binding.chargeCounterChartCard.visibility = android.view.View.GONE
        }

        // Current 차트 (데이터가 있는 경우)
        val hasCurrent = measurements.any { it.current != null }
        if (hasCurrent) {
            binding.currentChartCard.visibility = android.view.View.VISIBLE
            setupCurrentChart(measurements, startTime)
        } else {
            binding.currentChartCard.visibility = android.view.View.GONE
        }
    }

    private fun setupBatteryPercentChart(
        measurements: List<BatteryMeasurement>,
        startTime: Long
    ) {
        val entries = measurements.mapIndexed { index, measurement ->
            val timeMinutes = ((measurement.timestamp - startTime) / 60000f)
            Entry(timeMinutes, measurement.percentage.toFloat())
        }

        val dataSet = LineDataSet(entries, "배터리 (%)").apply {
            color = ContextCompat.getColor(this@SessionDetailActivity, R.color.health_good)
            setCircleColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.health_good))
            lineWidth = 2f
            circleRadius = 3f
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor = ContextCompat.getColor(this@SessionDetailActivity, R.color.health_good)
            fillAlpha = 50
        }

        binding.batteryPercentChart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return "${value.toInt()}분"
                    }
                }
            }
            axisLeft.apply {
                setDrawGridLines(true)
                axisMinimum = 0f
                axisMaximum = 100f
                granularity = 10f
            }
            axisRight.isEnabled = false
            legend.isEnabled = true
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            invalidate()
        }
    }

    private fun setupTemperatureChart(
        measurements: List<BatteryMeasurement>,
        startTime: Long
    ) {
        val entries = measurements.mapIndexed { index, measurement ->
            val timeMinutes = ((measurement.timestamp - startTime) / 60000f)
            Entry(timeMinutes, measurement.temperature)
        }

        val dataSet = LineDataSet(entries, "온도 (°C)").apply {
            color = ContextCompat.getColor(this@SessionDetailActivity, R.color.health_fair)
            setCircleColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.health_fair))
            lineWidth = 2f
            circleRadius = 3f
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        binding.temperatureChart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return "${value.toInt()}분"
                    }
                }
            }
            axisLeft.apply {
                setDrawGridLines(true)
                granularity = 5f
            }
            axisRight.isEnabled = false
            legend.isEnabled = true
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            invalidate()
        }
    }

    private fun setupVoltageChart(
        measurements: List<BatteryMeasurement>,
        startTime: Long
    ) {
        val entries = measurements.mapIndexed { index, measurement ->
            val timeMinutes = ((measurement.timestamp - startTime) / 60000f)
            Entry(timeMinutes, measurement.voltage.toFloat())
        }

        val dataSet = LineDataSet(entries, "전압 (mV)").apply {
            color = ContextCompat.getColor(this@SessionDetailActivity, R.color.primary)
            setCircleColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.primary))
            lineWidth = 2f
            circleRadius = 3f
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        binding.voltageChart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return "${value.toInt()}분"
                    }
                }
            }
            axisLeft.apply {
                setDrawGridLines(true)
                granularity = 100f
            }
            axisRight.isEnabled = false
            legend.isEnabled = true
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            invalidate()
        }
    }

    private fun setupChargeCounterChart(
        measurements: List<BatteryMeasurement>,
        startTime: Long
    ) {
        val entries = measurements.mapNotNull { measurement ->
            measurement.chargeCounter?.let {
                val timeMinutes = ((measurement.timestamp - startTime) / 60000f)
                Entry(timeMinutes, (it / 1000f)) // Convert to mAh
            }
        }

        if (entries.isEmpty()) return

        val dataSet = LineDataSet(entries, "누적 충전량 (mAh)").apply {
            color = ContextCompat.getColor(this@SessionDetailActivity, R.color.accent)
            setCircleColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.accent))
            lineWidth = 2f
            circleRadius = 3f
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        binding.chargeCounterChart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return "${value.toInt()}분"
                    }
                }
            }
            axisLeft.apply {
                setDrawGridLines(true)
            }
            axisRight.isEnabled = false
            legend.isEnabled = true
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            invalidate()
        }
    }

    private fun setupCurrentChart(
        measurements: List<BatteryMeasurement>,
        startTime: Long
    ) {
        val entries = measurements.mapNotNull { measurement ->
            measurement.current?.let {
                val timeMinutes = ((measurement.timestamp - startTime) / 60000f)
                Entry(timeMinutes, (it / 1000f)) // Convert to mA
            }
        }

        if (entries.isEmpty()) return

        val dataSet = LineDataSet(entries, "전류 (mA)").apply {
            color = ContextCompat.getColor(this@SessionDetailActivity, R.color.health_excellent)
            setCircleColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.health_excellent))
            lineWidth = 2f
            circleRadius = 3f
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        binding.currentChart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return "${value.toInt()}분"
                    }
                }
            }
            axisLeft.apply {
                setDrawGridLines(true)
            }
            axisRight.isEnabled = false
            legend.isEnabled = true
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            invalidate()
        }
    }

    private fun displayStatistics(measurements: List<BatteryMeasurement>) {
        binding.apply {
            // 측정 횟수
            measurementCountText.text = "${measurements.size}회"

            // 측정 간격
            if (measurements.size >= 2) {
                val intervals = measurements.zipWithNext { a, b ->
                    (b.timestamp - a.timestamp) / 1000 // seconds
                }
                val avgInterval = intervals.average()
                measurementIntervalText.text = String.format(
                    Locale.getDefault(),
                    "%.0f초",
                    avgInterval
                )
            }

            // 온도 통계
            val temps = measurements.map { it.temperature }
            tempMinText.text = String.format(
                Locale.getDefault(),
                "%.1f°C",
                temps.minOrNull() ?: 0f
            )
            tempMaxText.text = String.format(
                Locale.getDefault(),
                "%.1f°C",
                temps.maxOrNull() ?: 0f
            )
            tempAvgText.text = String.format(
                Locale.getDefault(),
                "%.1f°C",
                temps.average()
            )

            // 전압 통계
            val voltages = measurements.map { it.voltage }
            voltageMinText.text = String.format(
                Locale.getDefault(),
                "%,d mV",
                voltages.minOrNull() ?: 0
            )
            voltageMaxText.text = String.format(
                Locale.getDefault(),
                "%,d mV",
                voltages.maxOrNull() ?: 0
            )
            voltageAvgText.text = String.format(
                Locale.getDefault(),
                "%,d mV",
                voltages.average().toInt()
            )

            // Charge Counter 통계 (있는 경우)
            val chargeCounters = measurements.mapNotNull { it.chargeCounter }
            if (chargeCounters.isNotEmpty()) {
                chargeCounterStatsLayout.visibility = android.view.View.VISIBLE
                val chargeCountersMah = chargeCounters.map { it / 1000 }
                chargeCounterMinText.text = String.format(
                    Locale.getDefault(),
                    "%,d mAh",
                    chargeCountersMah.minOrNull() ?: 0
                )
                chargeCounterMaxText.text = String.format(
                    Locale.getDefault(),
                    "%,d mAh",
                    chargeCountersMah.maxOrNull() ?: 0
                )
            } else {
                chargeCounterStatsLayout.visibility = android.view.View.GONE
            }

            // Current 통계 (있는 경우)
            val currents = measurements.mapNotNull { it.current }
            if (currents.isNotEmpty()) {
                currentStatsLayout.visibility = android.view.View.VISIBLE
                val currentsMa = currents.map { it / 1000 }
                currentMinText.text = String.format(
                    Locale.getDefault(),
                    "%,d mA",
                    currentsMa.minOrNull() ?: 0
                )
                currentMaxText.text = String.format(
                    Locale.getDefault(),
                    "%,d mA",
                    currentsMa.maxOrNull() ?: 0
                )
                currentAvgText.text = String.format(
                    Locale.getDefault(),
                    "%,d mA",
                    currentsMa.average().toInt()
                )
            } else {
                currentStatsLayout.visibility = android.view.View.GONE
            }
        }
    }

    private fun displayMeasurementsList(measurements: List<BatteryMeasurement>) {
        // 측정 데이터 요약 표시
        val summary = buildString {
            appendLine("📊 측정 데이터 요약")
            appendLine()
            appendLine("총 측정 횟수: ${measurements.size}회")

            if (measurements.isNotEmpty()) {
                val startTime = measurements.first().timestamp
                val endTime = measurements.last().timestamp
                val duration = (endTime - startTime) / 1000 / 60 // minutes

                appendLine("측정 시간: ${duration}분")
                appendLine()

                // 배터리 변화
                val startPercent = measurements.first().percentage
                val endPercent = measurements.last().percentage
                appendLine("배터리: $startPercent% → $endPercent%")

                // 온도 변화
                val startTemp = measurements.first().temperature
                val endTemp = measurements.last().temperature
                appendLine("온도: ${String.format("%.1f", startTemp)}°C → ${String.format("%.1f", endTemp)}°C")

                // 전압 변화
                val startVoltage = measurements.first().voltage
                val endVoltage = measurements.last().voltage
                appendLine("전압: ${String.format("%,d", startVoltage)}mV → ${String.format("%,d", endVoltage)}mV")
            }
        }

        binding.measurementsSummaryText.text = summary
    }

    private fun showNoMeasurementsMessage() {
        binding.apply {
            noMeasurementsLayout.visibility = android.view.View.VISIBLE
            chartsScrollView.visibility = android.view.View.GONE
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
    }
}