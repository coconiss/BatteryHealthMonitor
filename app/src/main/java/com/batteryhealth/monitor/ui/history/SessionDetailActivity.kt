// ui/history/SessionDetailActivity.kt (새 파일)
package com.batteryhealth.monitor.ui.history

import android.os.Bundle
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.batteryhealth.monitor.R
import com.batteryhealth.monitor.databinding.ActivitySessionDetailBinding
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class SessionDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionDetailBinding
    private val viewModel: SessionDetailViewModel by viewModels()

    private val dateFormat = SimpleDateFormat("yyyy년 M월 d일 HH:mm:ss", Locale.KOREAN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (sessionId == -1L) {
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
            session?.let { displaySessionInfo(it) }
        }

        viewModel.measurements.observe(this) { measurements ->
            if (measurements.isNotEmpty()) {
                displayCharts(measurements)
                displayStatistics(measurements)
            }
        }
    }

    private fun displaySessionInfo(session: com.batteryhealth.monitor.data.local.entity.ChargingSession) {
        binding.apply {
            // 기본 정보
            sessionDateText.text = dateFormat.format(Date(session.startTimestamp))

            session.endTimestamp?.let { endTime ->
                val duration = endTime - session.startTimestamp
                val hours = TimeUnit.MILLISECONDS.toHours(duration)
                val minutes = TimeUnit.MILLISECONDS.toMinutes(duration) % 60
                durationText.text = "${hours}시간 ${minutes}분"
            }

            val endPercentage = session.endPercentage ?: session.startPercentage
            val chargeChange = endPercentage - session.startPercentage
            batteryChangeText.text = "${session.startPercentage}% → ${endPercentage}% (+${chargeChange}%)"

            session.estimatedCapacity?.let {
                estimatedCapacityText.text = "$it mAh"
            }

            avgTemperatureText.text = "${String.format("%.1f", session.averageTemperature)}°C"
            maxTemperatureText.text = "${String.format("%.1f", session.maxTemperature)}°C"
            avgVoltageText.text = "${session.averageVoltage} mV"

            session.chargerType?.let {
                chargerTypeText.text = it
            }

            if (session.isValid) {
                statusText.text = "유효"
                statusText.setTextColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.health_good))
            } else {
                statusText.text = "무효: ${session.invalidReason}"
                statusText.setTextColor(ContextCompat.getColor(this@SessionDetailActivity, R.color.health_poor))
            }
        }
    }

    private fun displayCharts(measurements: List<com.batteryhealth.monitor.data.local.entity.BatteryMeasurement>) {
        val startTime = measurements.first().timestamp

        // 온도 차트
        setupTemperatureChart(measurements, startTime)

        // 전압 차트
        setupVoltageChart(measurements, startTime)

        // 배터리 퍼센트 차트
        setupBatteryPercentChart(measurements, startTime)
    }

    private fun setupTemperatureChart(
        measurements: List<com.batteryhealth.monitor.data.local.entity.BatteryMeasurement>,
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
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return "${value.toInt()}분"
                    }
                }
            }
            axisLeft.setDrawGridLines(true)
            axisRight.isEnabled = false
            legend.isEnabled = true
            invalidate()
        }
    }

    private fun setupVoltageChart(
        measurements: List<com.batteryhealth.monitor.data.local.entity.BatteryMeasurement>,
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
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return "${value.toInt()}분"
                    }
                }
            }
            axisLeft.setDrawGridLines(true)
            axisRight.isEnabled = false
            legend.isEnabled = true
            invalidate()
        }
    }

    private fun setupBatteryPercentChart(
        measurements: List<com.batteryhealth.monitor.data.local.entity.BatteryMeasurement>,
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
        }

        binding.batteryPercentChart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
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
            }
            axisRight.isEnabled = false
            legend.isEnabled = true
            invalidate()
        }
    }

    private fun displayStatistics(measurements: List<com.batteryhealth.monitor.data.local.entity.BatteryMeasurement>) {
        binding.apply {
            // 온도 통계
            val temps = measurements.map { it.temperature }
            tempMinText.text = "${String.format("%.1f", temps.minOrNull() ?: 0f)}°C"
            tempMaxText.text = "${String.format("%.1f", temps.maxOrNull() ?: 0f)}°C"
            tempAvgText.text = "${String.format("%.1f", temps.average())}°C"

            // 전압 통계
            val voltages = measurements.map { it.voltage }
            voltageMinText.text = "${voltages.minOrNull() ?: 0} mV"
            voltageMaxText.text = "${voltages.maxOrNull() ?: 0} mV"
            voltageAvgText.text = "${voltages.average().toInt()} mV"

            // 측정 횟수
            measurementCountText.text = "${measurements.size}회"
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