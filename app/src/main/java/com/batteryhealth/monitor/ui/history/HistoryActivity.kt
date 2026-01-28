// ui/history/HistoryActivity.kt
package com.batteryhealth.monitor.ui.history

import android.os.Bundle
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.batteryhealth.monitor.databinding.ActivityHistoryBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val viewModel: HistoryViewModel by viewModels()
    private lateinit var adapter: ChargingSessionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupObservers()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "충전 기록"
            setDisplayHomeAsUpEnabled(true)
        }
    }

    private fun setupRecyclerView() {
        adapter = ChargingSessionAdapter()
        binding.sessionsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = this@HistoryActivity.adapter
        }
    }

    private fun setupObservers() {
        viewModel.sessions.observe(this) { sessions ->
            if (sessions.isEmpty()) {
                binding.emptyStateLayout.visibility = android.view.View.VISIBLE
                binding.sessionsRecyclerView.visibility = android.view.View.GONE
            } else {
                binding.emptyStateLayout.visibility = android.view.View.GONE
                binding.sessionsRecyclerView.visibility = android.view.View.VISIBLE
                adapter.submitList(sessions)
            }
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
}