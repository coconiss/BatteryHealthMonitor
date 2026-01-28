// ui/history/ChargingSessionAdapter.kt
package com.batteryhealth.monitor.ui.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.batteryhealth.monitor.R
import com.batteryhealth.monitor.data.local.entity.ChargingSession
import com.batteryhealth.monitor.databinding.ItemChargingSessionBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class ChargingSessionAdapter : ListAdapter<ChargingSession, ChargingSessionAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChargingSessionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemChargingSessionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("yyyy년 M월 d일 HH:mm", Locale.KOREAN)

        fun bind(session: ChargingSession) {
            binding.apply {
                // 날짜
                dateText.text = dateFormat.format(Date(session.startTimestamp))

                // 충전 시간
                session.endTimestamp?.let { endTime ->
                    val duration = endTime - session.startTimestamp
                    val hours = TimeUnit.MILLISECONDS.toHours(duration)
                    val minutes = TimeUnit.MILLISECONDS.toMinutes(duration) % 60
                    durationText.text = "${hours}시간 ${minutes}분"
                }

                // 배터리 변화
                val endPercentage = session.endPercentage ?: 0
                val change = endPercentage - session.startPercentage
                batteryChangeText.text = "${session.startPercentage}% → ${endPercentage}% (+${change}%)"

                // 추정 용량
                if (session.estimatedCapacity != null) {
                    capacityLayout.visibility = View.VISIBLE
                    estimatedCapacityText.text = "${session.estimatedCapacity} mAh"
                } else {
                    capacityLayout.visibility = View.GONE
                }

                // 온도
                temperatureText.text = "${String.format("%.1f", session.averageTemperature)}°C"

                // 충전기 타입
                if (session.chargerType != null) {
                    chargerLayout.visibility = View.VISIBLE
                    chargerTypeText.text = session.chargerType
                } else {
                    chargerLayout.visibility = View.GONE
                }

                // 유효성 표시
                if (session.isValid) {
                    statusIcon.setImageResource(android.R.drawable.checkbox_on_background)
                    statusIcon.setColorFilter(
                        ContextCompat.getColor(root.context, R.color.health_good)
                    )
                    invalidReasonText.visibility = View.GONE
                } else {
                    statusIcon.setImageResource(android.R.drawable.ic_delete)
                    statusIcon.setColorFilter(
                        ContextCompat.getColor(root.context, R.color.health_poor)
                    )
                    invalidReasonText.visibility = View.VISIBLE
                    invalidReasonText.text = "무효: ${session.invalidReason}"
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ChargingSession>() {
        override fun areItemsTheSame(oldItem: ChargingSession, newItem: ChargingSession): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChargingSession, newItem: ChargingSession): Boolean {
            return oldItem == newItem
        }
    }
}